package io.agentflow.onboarding;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentflow.calendar.BusinessCalendarService;
import io.agentflow.common.Actor;
import io.agentflow.common.DomainException;
import io.agentflow.notification.NotificationChannel;
import io.agentflow.notification.NotificationDestinations;
import io.agentflow.notification.NotificationPreferences;
import io.agentflow.notification.NotificationPreferencesService;
import io.agentflow.organization.InitiatorContext;
import io.agentflow.organization.OrganizationInitiatorDirectory;
import io.agentflow.organization.OrganizationPerson;
import io.agentflow.organization.OrganizationRepository;
import io.agentflow.organization.OrganizationService;
import io.agentflow.organization.OrganizationUnit;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 初始化只编排现有聚合用例，以目录锁串行化首次配置，所有落库与回执共用事务。
 * @author owlzhangfq@gmail.com
 */
@Service
public class TenantInitializationService {
    private final TenantInitializationRepository initializations;
    private final OrganizationRepository directory;
    private final OrganizationService organizations;
    private final OrganizationInitiatorDirectory initiators;
    private final BusinessCalendarService calendars;
    private final NotificationPreferencesService notifications;
    private final NotificationDestinations destinations;

    /** 复用组织、日历与本人通知用例，避免在初始化中复制其业务规则。 */
    public TenantInitializationService(TenantInitializationRepository initializations, OrganizationRepository directory,
                                        OrganizationService organizations, OrganizationInitiatorDirectory initiators,
                                        BusinessCalendarService calendars, NotificationPreferencesService notifications,
                                        NotificationDestinations destinations) {
        this.initializations = initializations; this.directory = directory; this.organizations = organizations;
        this.initiators = initiators; this.calendars = calendars; this.notifications = notifications; this.destinations = destinations;
    }

    /** 仅查看当前可信身份、本人选择与受控渠道是否配置，不连接外部服务。 */
    @Transactional(readOnly = true)
    public State state(Actor actor) {
        return new State(actor.tenantId(), actor.userId(), actor.roles(), directory.revision(actor.tenantId()),
                directory.personBySubject(actor.tenantId(), actor.userId()).orElse(null),
                initializations.find(actor.tenantId()).orElse(null), notifications.get(actor),
                List.of(binding(actor, NotificationChannel.EMAIL), binding(actor, NotificationChannel.ENTERPRISE_IM)));
    }

    /** 一次完成真实资源与历史保存；已有初始化只允许原幂等请求回放，不能再次运行。 */
    @Transactional
    public TenantInitialization initialize(Actor actor, InitializationRequest input) {
        requireUninitialized(actor);
        long current = directory.revision(actor.tenantId());
        if (current == 0) {
            requireRevision(input.expectedOrganizationRevision(), 0);
            organizations.initialize(actor);
            directory.lock(actor.tenantId());
        } else {
            current = directory.lock(actor.tenantId());
            requireUninitialized(actor);
            requireRevision(input.expectedOrganizationRevision(), current);
        }
        var choice = input.notifications();
        verifyBinding(actor, NotificationChannel.EMAIL, choice.emailEnabled(), choice.emailBindingDigest());
        verifyBinding(actor, NotificationChannel.ENTERPRISE_IM, choice.enterpriseImEnabled(), choice.enterpriseImBindingDigest());
        InitiatorContext context = input.organization().source() == InitializationRequest.Source.EXISTING
                ? initiators.snapshot(actor, input.organization().appointmentId()) : createOrganization(actor, input.organization());
        var calendarChoice = input.calendar();
        var calendar = calendarChoice.source() == InitializationRequest.Source.EXISTING
                ? calendars.version(actor.tenantId(), calendarChoice.id(), calendarChoice.revision())
                : calendars.create(actor, calendarChoice.key(), calendarChoice.name(), calendarChoice.rules());
        var preferences = notifications.revise(actor, choice.expectedVersion(), choice.emailEnabled(), choice.enterpriseImEnabled());
        String administratorName = directory.person(actor.tenantId(), context.personId()).orElseThrow().displayName();
        var result = TenantInitialization.completed(actor, input.workspaceName(), context, administratorName, calendar, preferences, Instant.now());
        initializations.append(result);
        return result;
    }

    private InitiatorContext createOrganization(Actor actor, InitializationRequest.OrganizationChoice input) {
        var person = directory.personBySubject(actor.tenantId(), actor.userId()).orElse(null);
        // 复用已有身份不能隐式更名、启用或赋予审批资格，状态变更必须回到组织管理用例。
        if (person != null && (!person.active() || !person.displayName().equals(input.administratorName().strip()))) {
            throw new DomainException("INITIALIZATION_PERSON_CHANGED", "Review the existing administrator person before initialization");
        }
        var legal = organizations.createUnit(actor, OrganizationUnit.Kind.LEGAL_ENTITY, input.legalEntityName(), null, null, true);
        var department = organizations.createUnit(actor, OrganizationUnit.Kind.DEPARTMENT, input.departmentName(), legal.id(), null, true);
        var position = organizations.createUnit(actor, OrganizationUnit.Kind.POSITION, input.positionName(), legal.id(), null, true);
        if (person == null) person = organizations.createPerson(actor, actor.userId(), input.administratorName(), true, actor.hasRole("APPROVER"));
        var appointment = organizations.createAppointment(actor, person.id(), department.id(), position.id(), true);
        return initiators.snapshot(actor, appointment.id());
    }

    private ChannelBinding binding(Actor actor, NotificationChannel channel) {
        return destinations.find(actor.tenantId(), actor.userId(), channel).filter(NotificationDestinations.Destination::enabled)
                .map(value -> new ChannelBinding(channel, true, value.digest())).orElse(new ChannelBinding(channel, false, null));
    }

    private void verifyBinding(Actor actor, NotificationChannel channel, boolean enabled, String expectedDigest) {
        if (!enabled) return;
        var current = binding(actor, channel);
        if (!current.configured() || !expectedDigest.equals(current.digest())) {
            throw new DomainException("INITIALIZATION_CHANNEL_CHANGED", "Review the current notification binding before enabling this channel");
        }
    }

    private void requireUninitialized(Actor actor) {
        if (initializations.find(actor.tenantId()).isPresent()) throw new DomainException("TENANT_ALREADY_INITIALIZED", "Tenant initialization is already complete");
    }

    private static void requireRevision(long expected, long current) {
        if (expected != current) throw new DomainException("CONCURRENCY_CONFLICT", "Organization directory changed; review initialization again");
    }

    /** 初始化事实与当前设置分开返回；快照不会伪装成当前组织或订阅状态。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record State(String tenantId, String currentSubject, Set<String> currentRoles, long organizationRevision,
                        OrganizationPerson currentAdministratorPerson, TenantInitialization initialization,
                        NotificationPreferences currentNotifications, List<ChannelBinding> channelBindings) { }

    /** 仅公布当前用户的配置存在性与摘要，配置存在不代表真实递送已验证。
     * @author owlzhangfq@gmail.com
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChannelBinding(NotificationChannel channel, boolean configured, String digest) { }
}

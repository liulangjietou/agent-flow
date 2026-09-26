package io.agentflow.notification;

import io.agentflow.common.DomainException;
import io.agentflow.definition.DefinitionModels.DefinitionDraft;
import io.agentflow.definition.DefinitionModels.Graph;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * 配置边界、草稿并发及发布不可变性，不执行模板或申请表达式。
 * @author owlzhangfq@gmail.com
 */
class NotificationTextsTest {
    @Test
    void treatsMarkupAndExpressionsAsLiteralTextButRejectsControlCodesAndOversizeValues() {
        var texts = new NotificationTexts(null, " \n\t ", "<script>${amount}</script>");
        assertThat(texts.submitted()).isEmpty();
        assertThat(texts.returned()).isEmpty();
        assertThat(texts.approved()).isEqualTo("<script>${amount}</script>");
        assertThat(new NotificationTexts("字".repeat(500), "", "").submitted()).hasSize(500);
        assertThatThrownBy(() -> new NotificationTexts("字".repeat(501), "", "")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new NotificationTexts("\u0000", "", "")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new NotificationTexts("", "\u0085", "")).isInstanceOf(DomainException.class);
        assertThat(texts.forEvent(InboxMessage.Kind.TASK_PENDING)).isEmpty();
    }

    @Test
    void omittedConfigurationPreservesTextsAndExplicitEmptyClearsThemUnderTheSameRevision() {
        var graph = new Graph(List.of(), List.of());
        var texts = new NotificationTexts("已提交", "请修改", "已批准");
        var draft = DefinitionDraft.create(UUID.randomUUID(), "demo", "notice", "通知", graph, null, texts);
        draft.update("新名称", graph, null, 0);
        assertThat(draft.notificationTexts()).isEqualTo(texts);
        assertThatThrownBy(() -> draft.update("冲突", graph, null, NotificationTexts.EMPTY, 0)).isInstanceOf(DomainException.class);
        assertThat(draft.notificationTexts()).isEqualTo(texts);
        draft.update("新名称", graph, null, NotificationTexts.EMPTY, 1);
        assertThat(draft.notificationTexts()).isEqualTo(NotificationTexts.EMPTY);
        draft.publish(2, 1);
        assertThatThrownBy(() -> draft.update("已发布", graph, null, texts, 3)).isInstanceOf(DomainException.class);
        assertThat(draft.notificationTexts()).isEqualTo(NotificationTexts.EMPTY);
    }
}

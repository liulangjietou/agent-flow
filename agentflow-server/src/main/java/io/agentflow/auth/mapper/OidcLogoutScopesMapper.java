package io.agentflow.auth.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * OidcLogoutScopes 的 SQL 映射，参与调用方已有的数据库事务。
 *
 * @author owlzhangfq@gmail.com
 */
@Mapper
public interface OidcLogoutScopesMapper {
    /** 读取 beginLogin 所需的持久化事实。 */
    List<Long> beginLogin();

    /** 读取 revoke 所需的持久化事实。 */
    List<Long> revoke();

    /** 读取 revoke 所需的持久化事实。 */
    List<Integer> revoke2(@Param("event") String event);

    /** 更新 revoke 所需的持久化事实。 */
    int revoke3(@Param("next") Long next);

    /** 新增 revoke 所需的持久化事实。 */
    int revoke4(@Param("eventHash") String eventHash);

    /** 更新 revoke 所需的持久化事实。 */
    int revoke5(
            @Param("issuedAt") Long issuedAt,
            @Param("issuedAt2") Long issuedAt2,
            @Param("next") Long next,
            @Param("scope") String scope);

    /** 新增 revoke 所需的持久化事实。 */
    int revoke6(
            @Param("scopeHash") String scopeHash,
            @Param("loggedOutAt") Long loggedOutAt,
            @Param("logoutOrder") Long logoutOrder);

    /** 读取 requireActive 所需的持久化事实。 */
    List<Integer> requireActive(
            @Param("subjectScope") String subjectScope,
            @Param("sidScope") String sidScope,
            @Param("combinedScope") String combinedScope,
            @Param("loginOrder") Long loginOrder);
}

package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentflow.common.DomainException;
import io.agentflow.common.JsonUtil;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import java.util.Map;

/**
 * 从不可变任职快照建立组织名称查询列；不读取当前组织目录或修改原轮次内容。
 * @author owlzhangfq@gmail.com
 */
public class V29__Index_round_organization extends BaseJavaMigration {
    private static final int MAX_NAME_LENGTH = 128;
    private static final int FETCH_SIZE = 256;

    /** 固定迁移内容版本，发布后不得跟随运行时模型改变。 */
    @Override
    public Integer getChecksum() { return 2026092801; }

    /** 分批回填完整有效的名称快照，缺失或损坏历史保持未记录。 */
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE approval_submission_round ADD COLUMN initiator_legal_entity_name VARCHAR(128)");
            statement.execute("ALTER TABLE approval_submission_round ADD COLUMN initiator_department_name VARCHAR(128)");
            statement.execute("ALTER TABLE approval_submission_round ADD COLUMN initiator_position_name VARCHAR(128)");
        }
        var json = new JsonUtil(new ObjectMapper());
        try (var select = connection.prepareStatement("SELECT tenant_id,application_id,round_no,initiator_context_json FROM approval_submission_round WHERE initiator_context_json IS NOT NULL");
             var update = connection.prepareStatement("""
                     UPDATE approval_submission_round SET initiator_legal_entity_name=?,initiator_department_name=?,initiator_position_name=?
                     WHERE tenant_id=? AND application_id=? AND round_no=?
                     """)) {
            select.setFetchSize(FETCH_SIZE);
            try (var rows = select.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> snapshot;
                    try { snapshot = json.map(rows.getString("initiator_context_json")); }
                    catch (DomainException malformedLegacySnapshot) { continue; }
                    if (snapshot == null || !validName(snapshot.get("legalEntityName"))
                            || !validName(snapshot.get("departmentName")) || !validName(snapshot.get("positionName"))) continue;
                    update.setString(1, (String) snapshot.get("legalEntityName"));
                    update.setString(2, (String) snapshot.get("departmentName"));
                    update.setString(3, (String) snapshot.get("positionName"));
                    update.setString(4, rows.getString("tenant_id"));
                    update.setString(5, rows.getString("application_id"));
                    update.setInt(6, rows.getInt("round_no"));
                    update.executeUpdate();
                }
            }
        }
    }

    private static boolean validName(Object value) {
        return value instanceof String name && !name.isBlank() && name.length() <= MAX_NAME_LENGTH
                && name.chars().noneMatch(Character::isISOControl);
    }
}

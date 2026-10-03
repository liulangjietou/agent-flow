package io.agentflow.servicetask;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 数据库迁移与应用初始化完成后安装可信声明，版本冲突使本次启动失败。
 * @author owlzhangfq@gmail.com
 */
@Component
public class ServiceTaskCatalogInstaller implements ApplicationRunner {
    private final ServiceTaskCatalog catalog;
    /** 安装用例经独立事务代理调用。 */
    public ServiceTaskCatalogInstaller(ServiceTaskCatalog catalog) { this.catalog = catalog; }
    /** 无声明时不写数据库。 */
    @Override public void run(ApplicationArguments args) { catalog.install(); }
}

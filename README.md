# asg-console-extension

ASG console 后端扩展模块（Spring Boot AutoConfiguration 自动装配）。

## 定位

- 承载 AISecGw-console 的全部 ASG 自研后端代码（controller / service / 定时任务 / 插件清单合并）。
- 对上游 higress-console 零侵入：依赖 `higress-admin-sdk` 提供的接口与 Bean，通过自动装配注入。
- 移除后 AISecGw-console 可回归纯上游（仅品牌层可重放文件）。

## 构建

```bash
# 依赖 higress-admin-sdk，先 install 到本地 m2（JDK11 + 跳过 pmd/checkstyle）
cd ../AISecGw-console/backend
JAVA_HOME=<JDK11> ./mvnw install -pl sdk -DskipTests -Dpmd.skip=true -Dcheckstyle.skip=true

# 本模块编译 + 单测
cd ../../asg-console-extension
mvn test
mvn package
```

## 接入

- AISecGw-console/backend/console/pom.xml 增加依赖 asg-console-extension 后，扩展自动生效。
- 自动装配注册：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。

# Wave 6 · 应用镜像（多阶段构建：编译与运行分离，最终镜像不带 Maven 与源码）
#
# 构建：docker build -t scenic-agent ./backend-java
# 运行：见仓库根目录 docker-compose.yml 的 app 服务（profile=full）

# ── 构建阶段 ──
# 用本机已有镜像的 tag（alpine 变体），避免构建时还要联网拉基础镜像
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app

# 先只拷 pom 并拉依赖：依赖没变时这层会被缓存，后续改代码不会重新下载
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# ── 运行阶段 ──
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 时区与编码：日志和定时相关逻辑都依赖它
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"

COPY --from=build /app/target/*.jar app.jar

EXPOSE 10086

# 容器内存由 MaxRAMPercentage 决定堆上限，避免写死 -Xmx 在换机器时失衡
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# 多阶段构建：先用 Maven 打包，再用轻量镜像运行
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
# 先复制 pom.xml 利用缓存，再复制源码
COPY pom.xml .
COPY src ./src
RUN set -eux; \
		for i in 1 2 3; do \
			mvn clean package -DskipTests && break; \
			echo "Maven build failed (attempt $i), retrying..."; \
			sleep $((i * 10)); \
		done; \
		cp target/*.jar app.jar

# 运行阶段沿用可稳定拉取的 Maven + JDK 镜像
FROM maven:3.9.6-eclipse-temurin-17
WORKDIR /app
COPY --from=builder /app/app.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "app.jar"]

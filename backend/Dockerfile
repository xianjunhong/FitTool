# 多阶段构建：先用 Maven 打包，再用轻量镜像运行
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
# 先复制 pom.xml 利用缓存，再复制源码
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q && cp target/*.jar app.jar

# 运行阶段使用 JRE 镜像，减小镜像体积
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/app.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "app.jar"]

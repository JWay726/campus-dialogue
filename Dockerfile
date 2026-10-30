# 多阶段构建：先用 Maven 编译打包，再用精简 JRE 运行
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
# 单独拷贝 pom 可利用镜像层缓存依赖
COPY pom.xml .
COPY src ./src
# 国内构建如需加速，可挂载阿里云镜像：docker build --build-arg ... 或挂载 settings.xml
RUN mvn -B -DskipTests clean package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

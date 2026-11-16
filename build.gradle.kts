plugins { java }
group = "fun.megalev"
version = "0.1.0"
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies { compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

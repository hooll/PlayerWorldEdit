import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import io.izzel.taboolib.gradle.*
import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.BukkitHook
import io.izzel.taboolib.gradle.BukkitUI
import io.izzel.taboolib.gradle.BukkitUtil
import io.izzel.taboolib.gradle.BukkitNMS
import io.izzel.taboolib.gradle.BukkitNMSUtil
import io.izzel.taboolib.gradle.BukkitNMSDataSerializer
import io.izzel.taboolib.gradle.MinecraftChat
import io.izzel.taboolib.gradle.MinecraftEffect
import io.izzel.taboolib.gradle.CommandHelper
import io.izzel.taboolib.gradle.Database
import io.izzel.taboolib.gradle.DatabasePlayer


plugins {
    java
    id("io.izzel.taboolib") version "2.0.39"
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

// 版本号 = 语义版本 + git 短哈希, 区分每次构建
version = runCatching {
    "1.2.1-" + providers.exec {
        commandLine("git", "rev-parse", "--short=7", "HEAD")
    }.standardOutput.asText.map { it.trim() }.get()
}.getOrDefault("1.2.1")

taboolib {
    env {
        install(Basic)
        install(Bukkit)
        install(BukkitHook)
        install(BukkitUI)
        install(BukkitUtil)
        install(BukkitNMS)
        install(BukkitNMSUtil)
        install(BukkitNMSDataSerializer)
        install(MinecraftChat)
        install(MinecraftEffect)
        install(CommandHelper)
        install(Database)
        install(DatabasePlayer)
    }
    description {
        name = "PlayerWorldEdit"
        contributors {
            name("Sting")
        }
        dependencies {
            name("WorldEdit")
            name("Dominion").optional(true)
            // Residence / WorldGuard 的领地保护钩子: 都按"没装就静默跳过"处理, 所以是 softdepend
            // (softdepend 同时让它们的类在运行期能被本插件的类加载器看到 —— Residence 钩子是反射,
            //  WorldGuard 钩子是 compileOnly 直接调用, 两种都需要这一条)
            name("Residence").optional(true)
            name("WorldGuard").optional(true)
        }
    }
    version { taboolib = "6.3.0-0e3a911" }
}

repositories {
    maven("https://maven.aliyun.com/repository/central")
    mavenCentral()
}

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:mapped")
    compileOnly("ink.ptms.core:v12004:12004:universal")
    compileOnly("cn.lunadeer:DominionAPI:4.9.6")
    implementation("com.google.code.gson:gson:2.10.1")
    taboo("top.maplex.arim:Arim:1.2.14")
    compileOnly(kotlin("stdlib"))
    compileOnly(fileTree("libs"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

task("copy") {
    //复制前执行构建
    dependsOn("clean")
    dependsOn("build")
    tasks.findByName("build")?.mustRunAfter("clean")
    mustRunAfter("build")
    //进行任务后进行复制
    doLast {
        copy {
            from("${buildDir.path}\\libs\\")
            into("D:\\Minecraft\\Server\\leaf1.20.4\\plugins\\update")
        }
    }
}

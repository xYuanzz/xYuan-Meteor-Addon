import org.gradle.api.Transformer
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.Properties

plugins {
    alias(libs.plugins.fabric.loom)
}

// ===========================================================================
//  1. 版本分发
//  发现 versions/*.properties，按 -Pminecraft_version 选定当前构建的 MC 版本。
//  未指定时默认构建版本号最高的那个（便于 IDE 直接导入与本地开发）。
// ===========================================================================

val modVersion: String = libs.versions.mod.version.get()

/**
 * 数字感知比较器。
 * 字符串排序会得到 1.21.1 < 1.21.11 < 1.21.4（因为 "1" < "4"），
 * 必须按.分段转数字再比，才能得到 1.21.1 < 1.21.4 < 1.21.11。
 */
val versionComparator = Comparator<String> { a, b ->
    val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
    val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
    var result = 0
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) {
            result = diff
            break
        }
    }
    result
}

val versionsDir = layout.projectDirectory.dir("versions")

val knownVersions: List<String> = (versionsDir.asFile.listFiles() ?: emptyArray())
    .filter { it.isFile && it.extension == "properties" }
    .map { it.name.removeSuffix(".properties") }
    .sortedWith(versionComparator)

val currentMcVersion: String =
    providers.gradleProperty("minecraft_version").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: knownVersions.maxWithOrNull(versionComparator)
        ?: error("versions/ 目录下没有任何 <mc>.properties 文件")

val versionProperties: Properties = Properties().apply {
    val file = versionsDir.file("$currentMcVersion.properties").asFile
    require(file.exists()) {
        "找不到 versions/$currentMcVersion.properties；已发现的版本：$knownVersions"
    }
    file.inputStream().use { load(it) }
}

/** 读取版本属性；缺失或为空视为配置错误，直接失败而不是把空串塞给依赖坐标。 */
fun vp(key: String): String = versionProperties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    ?: error("versions/$currentMcVersion.properties 缺少必需属性：$key")

/** 1.21.11 -> 12111，与源码目录 src/main/java/cn/anyho/xyuan/v12111 对应。 */
val versionSuffix: String = currentMcVersion.replace(".", "")

/**
 * 嵌套构建（buildFor* 传入 -Pbuild_dir）时，把中间产物重定向到独立目录。
 * 否则每个版本的 clean 都会清掉同一个 build/，把上一个版本的产物一起删掉。
 * 注意：这段必须早于任何对 buildDirectory 的求值。
 */
providers.gradleProperty("build_dir").orNull?.let { layout.buildDirectory.set(file(it)) }

val pkgPath = "cn/anyho/xyuan"
val pkgName = "cn.anyho.xyuan"
val versionSourceDir = layout.projectDirectory.dir("src/main/java/$pkgPath/v$versionSuffix")
/**
 * 产物基名。
 * ⚠️ 不要改名为 archiveBaseName —— Jar 任务（AbstractArchiveTask）本身就有同名属性，
 * 在 tasks.named<Jar> { } 这类块里会被隐式接收者优先解析成那个 Property<String>，
 * 字符串模板会把它插值成 "task ':jar' property 'archiveBaseName'"，导致 jar 条目名非法。
 */
val jarBaseName = "${providers.gradleProperty("mod_archive_name").get()}-$currentMcVersion"

/** 各版本产物的汇总目录，始终位于项目下的 build/versions/，不受 build_dir 重定向影响。 */
val versionOutputRoot = layout.projectDirectory.dir("build/versions")

// ===========================================================================
//  2. 基础信息与依赖
// ===========================================================================

base {
    archivesName = jarBaseName
    version = modVersion
    group = providers.gradleProperty("maven_group").get()
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    // 全部坐标来自 versions/<mc>.properties，不再走 libs.versions.toml
    minecraft("com.mojang:minecraft:${vp("minecraft_version")}")
    mappings("net.fabricmc:yarn:${vp("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${vp("loader_version")}")
    modImplementation("meteordevelopment:meteor-client:${vp("meteor_version")}")

    // Fabric API 默认不引入：实测 Meteor Client 的 jar 内 META-INF/jars 已内置它真正用到的
    // API 模块（fabric-api-base、fabric-resource-loader-v1），单独引入反而可能造成版本冲突。
    // 确需引入时打开下面一行（坐标已在各 versions/*.properties 中登记并核实存在）：
    // modImplementation("net.fabricmc.fabric-api:fabric-api:${vp("fabric_api_version")}")
}

// ===========================================================================
//  3. 编译期版本隔离
//  把当前版本目录下的实现复制到共享包，只重写 package 声明。
//  产物写到 build/generated/ 而非回写 src/，避免污染工作区。
// ===========================================================================

val generatedRoot = layout.buildDirectory.dir("generated")

/**
 * 版本私有目录名 -> 目标包名。
 * 将来若某个 mixin 需要按版本分叉，在 v<ver>/mixin/ 下放类，
 * 并在此处加一行 "mixin" to "$pkgName.mixin" 即可（exclude 规则已覆盖整个 v<ver>/ 目录）。
 * 注意：Kotlin 的块注释可以嵌套，注释里不要出现斜杠加星号的组合。
 */
val versionPrivatePackages = mapOf(
    "compat" to "$pkgName.compat",
)

val generateVersionSources = tasks.register<Sync>("generateVersionSources") {
    group = "build"
    description = "把 v$versionSuffix 下的版本实现复制到共享包（重写 package 声明）"

    versionPrivatePackages.forEach { (subPkg, targetPackage) ->
        from(versionSourceDir.dir(subPkg)) {
            include("**/*.java")
            into("$pkgPath/$subPkg")
            // CopySpec.filter 期望的是 Transformer<String?, String>（入参可空），
            // 必须显式构造并处理 null，否则会与 filter(Class<FilterReader>) 重载歧义。
            filter(Transformer<String?, String> { line ->
                line.orEmpty()
                    .replace("package $pkgName.v$versionSuffix.$subPkg", "package $targetPackage")
            })
        }
    }
    into(generatedRoot.map { it.dir("versionSources") })
}

sourceSets.named("main").configure {
    // 关键：所有版本私有目录一律排除。
    // 否则生成的 Via.java 会与 v<ver>/compat/Via.java 在同一包下形成重复类，直接编译失败。
    knownVersions.forEach { v -> java.exclude("$pkgPath/v${v.replace(".", "")}/**") }
    java.srcDir(generatedRoot.map { it.dir("versionSources") })
}

// ===========================================================================
//  4. 编译 / 资源 / 打包
// ===========================================================================

tasks.withType<JavaCompile>().configureEach {
    dependsOn(generateVersionSources)
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.named<ProcessResources>("processResources") {
    val propertyMap = mapOf(
        "version" to modVersion,
        "mc_version" to currentMcVersion,
    )
    inputs.properties(propertyMap)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(propertyMap)
    }
}

tasks.named<Jar>("jar") {
    from("LICENSE") {
        rename { "${it}_$jarBaseName" }
    }
}

// ===========================================================================
//  5. 多版本构建任务
//
//  为什么用 Exec 而不是 GradleBuild（重要，别改回去）：
//  同一个 Gradle 调用里，对同一个项目目录只能注册一个 GradleBuild 嵌套构建，
//  第二个会直接失败：
//    Included build <dir> has build path :<name> which is the same as included build <dir>
//  buildAll 需要在一个调用里串行跑多个版本，所以必须用 Exec 起子进程。
//
//  为什么不用 Exec("gradlew")：
//  本仓库未提交 gradle wrapper，干净克隆上没有 gradlew。这里用 gradle.gradleHomeDir
//  定位「当前正在运行的那套 Gradle 发行版」的启动器，既不需要 wrapper 也不硬编码路径。
// ===========================================================================

val projectRoot = layout.projectDirectory.asFile
val versionOutputRootFile = versionOutputRoot.asFile

/**
 * 当前 Gradle 发行版的启动器路径（Windows 为 gradle.bat，其余平台为 gradle）。
 *
 * ⚠️ 这里刻意允许为 null，并把「取不到」的失败推迟到任务执行期（见 buildFor* 里的 onlyIf）：
 * gradle.gradleHomeDir 在 Gradle Tooling API 下为 null（IntelliJ 的 Gradle 导入/同步走的就是 Tooling API）。
 * 若在配置期就 error()，失败的是【整个配置阶段】—— compileJava、build、IDE 同步统统报错，
 * 而不只是 buildFor*。所以这里绝不能提前抛。
 */
val gradleLauncher: String? = gradle.gradleHomeDir?.let { home ->
    val launcherName =
        if (System.getProperty("os.name").lowercase().contains("windows")) "gradle.bat" else "gradle"
    File(home, "bin/$launcherName").absolutePath
}

val launcherUnavailableMessage =
    "buildFor* / buildAll 需要启动一个 Gradle 子进程，但无法确定 Gradle 安装目录" +
        "（gradle.gradleHomeDir 为 null，常见于 IDE 的 Gradle Tooling API）。" +
        "请在命令行或 CI 中执行这些任务。"

val buildForTasks: Map<String, TaskProvider<Exec>> = knownVersions.associateWith { mc ->
    val suffix = mc.replace(".", "")
    // 配置期捕获一次，避免任务体内反复读取可变状态
    val launcher = gradleLauncher
    tasks.register<Exec>("buildFor$suffix") {
        group = "build"
        description = "构建 Minecraft $mc（最终产物输出到 build/versions/$mc/）"

        // 只有真正要跑这个任务时才因「拿不到 Gradle 启动器」失败，
        // 不影响 IDE 同步与其他任务（理由见 gradleLauncher 的注释）
        onlyIf {
            if (launcher == null) throw GradleException(launcherUnavailableMessage)
            true
        }

        workingDir = projectRoot
        commandLine(
            launcher ?: "gradle",
            "clean",
            "build",
            "-Pminecraft_version=$mc",
            // 中间产物进 .work/ 子目录，最终 jar 拷到父目录。
            // 两者若同目录，下一个版本的 clean 会连带删掉上一个版本的产物。
            "-Pbuild_dir=${File(versionOutputRootFile, "$mc/.work").absolutePath}",
            // 各版本独立的 Gradle 项目缓存目录，避免与父构建抢 .gradle 下的文件锁
            "--project-cache-dir=${File(projectRoot, ".gradle/versions/$mc").absolutePath}",
            "--console=plain",
        )

        doLast {
            val srcDir = File(versionOutputRootFile, "$mc/.work/libs")
            val destDir = File(versionOutputRootFile, mc)
            destDir.mkdirs()
            // 先清掉上一轮遗留的 jar。
            // 子构建的 clean 只清 .work/，管不到这个父目录；不改产物名时会被覆盖，
            // 但一旦改了 mod 版本号（如 1.3.0 → 1.3.1），旧 jar 会一直留着，
            // 于是 build/versions/<mc>/ 就不再代表「本次构建的结果」。
            destDir.listFiles { f -> f.isFile && f.extension == "jar" }?.forEach { it.delete() }
            val jars = srcDir.listFiles { f ->
                f.isFile && f.extension == "jar" &&
                    !f.name.endsWith("-dev.jar") && !f.name.endsWith("-sources.jar")
            } ?: emptyArray()
            if (jars.isEmpty()) {
                throw GradleException("MC $mc 未产出 jar，请检查目录：$srcDir")
            }
            jars.forEach { it.copyTo(File(destDir, it.name), overwrite = true) }
            logger.lifecycle("MC $mc -> build/versions/$mc/${jars.joinToString(", ") { it.name }}")
        }
    }
}

// 串行化：多个子构建并行会互相抢内存与 Gradle 缓存
val orderedBuildTasks = knownVersions.map { buildForTasks.getValue(it) }
for (i in 1 until orderedBuildTasks.size) {
    orderedBuildTasks[i].configure { mustRunAfter(orderedBuildTasks[i - 1]) }
}

tasks.register("buildAll") {
    group = "build"
    description = "构建 versions/ 下全部版本，产物汇总到 build/versions/<mc>/"
    dependsOn(orderedBuildTasks)
}

// ===========================================================================
//  6. 辅助任务
// ===========================================================================

tasks.register("printVersions") {
    group = "help"
    description = "打印当前选中的 MC 版本与解析出的依赖坐标"

    val mc = currentMcVersion
    val suffix = versionSuffix
    val all = knownVersions
    val yarnMappings = vp("yarn_mappings")
    val loader = vp("loader_version")
    val meteor = vp("meteor_version")
    val artifact = "$jarBaseName-$modVersion.jar"

    doLast {
        println("当前版本      : $mc   (版本实现目录 v$suffix)")
        println("已发现版本    : ${all.joinToString(", ")}")
        println("yarn_mappings : $yarnMappings")
        println("loader        : $loader")
        println("meteor        : $meteor")
        println("产物名        : $artifact")
    }
}

// ===========================================================================
//  7. 版本实现一致性校验
//
//  v1211 与 v1214 用到的原版 API 面完全相同（NbtCompound 的带默认值重载、getListOrEmpty、
//  asString()->Optional 都是 1.21.5 才加入），所以这两份实现除 package 与版本号外必须逐字一致。
//  两份副本最典型的失效方式是「改了一份忘另一份」，这里在构建期直接断言，而不是指望人记得。
//  校验挂在 check 上，因此 `build` / CI 都会执行。
// ===========================================================================

val paritySuffixes = listOf("v1211", "v1214")

val verifyVersionParity = tasks.register("verifyVersionParity") {
    group = "verification"
    description = "校验 $paritySuffixes 的兼容层实现是否仅在 package 与版本号上不同"

    val javaRoot = layout.projectDirectory.dir("src/main/java/$pkgPath").asFile

    doLast {
        val problems = mutableListOf<String>()

        /** 去掉注释与空白差异后归一化 package 段、版本号字面量与已知 API 差异，只比较「代码本身」。 */
        fun code(text: String, suffix: String): String = text
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .filterNot { it.trim().startsWith("//") }
            .joinToString("\n")
            .replace("$pkgName.$suffix.", "$pkgName.")
            .replace(Regex("\"1\\.21\\.[0-9]+\""), "\"VERSION\"")
            // SoundEvent 的访问器是这两个版本唯一的真实 API 差异：
            // 1.21.1 是普通类只有 getId()，1.21.4 起是 record 只有 id()。
            // 归一化掉这一处之后，其余代码必须逐字一致。
            .replace("event.getId()", "event.SOUND_EVENT_ID()")
            .replace("event.id()", "event.SOUND_EVENT_ID()")
            .replace(Regex("\\s+"), " ")
            .trim()

        versionPrivatePackages.keys.forEach { subPkg ->
            val fileNames = paritySuffixes
                .flatMap { suffix ->
                    File(javaRoot, "$suffix/$subPkg")
                        .listFiles { f -> f.isFile && f.extension == "java" }
                        ?.map { it.name } ?: emptyList()
                }
                .toSortedSet()

            fileNames.forEach { fileName ->
                val textBySuffix = paritySuffixes.associateWith { suffix ->
                    File(javaRoot, "$suffix/$subPkg/$fileName").takeIf { it.isFile }?.readText()
                }
                val missing = textBySuffix.filterValues { it == null }.keys
                if (missing.isNotEmpty()) {
                    problems += "$subPkg/$fileName 在 ${missing.joinToString(", ")} 下不存在"
                    return@forEach
                }
                val a = code(textBySuffix.getValue("v1211")!!, "v1211")
                val b = code(textBySuffix.getValue("v1214")!!, "v1214")
                if (a != b) {
                    problems += "$subPkg/$fileName 的两份实现在代码层面不一致（应只差 package 与版本号）"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "版本实现一致性校验失败：\n  - " + problems.joinToString("\n  - ") +
                    "\n请同步修改 ${paritySuffixes.joinToString(" 与 ")} 下的对应文件。"
            )
        }
        logger.lifecycle("版本实现一致性校验通过：$paritySuffixes 下的实现仅在 package、版本号与 SoundEvent 访问器上不同")
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyVersionParity) }

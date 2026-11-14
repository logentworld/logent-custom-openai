// 테스트·릴리스가 같은 AAR로 .lpg만 만들도록 모듈 안에서 등록해요.
val repository = projectDir.parentFile
val releaseVersion = repository.resolve("VERSION").readText().trim()
require(Regex("[0-9]+[.][0-9]+[.][0-9]+").matches(releaseVersion)) { "VERSION은 x.y.z 형식으로 써 주세요." }
val distribution = repository.resolve("build/dist")
val pluginDefinition = repository.resolve("logent-plugin.json")
val pluginPackage = tasks.register<Exec>("pluginPackage") {
    group = "distribution"
    description = "classes.jar를 D8로 변환하고 manifest와 묶어 .lpg를 만들어요."
    dependsOn("assembleRelease")
    val aar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar").get().asFile
    val lpg = distribution.resolve("logent-custom-openai-v${releaseVersion}.lpg")
    doFirst {
        if (!aar.isFile) throw GradleException("AAR이 없어요: $aar")
        distribution.mkdirs()
        commandLine(
            "python3",
            repository.resolve("scripts/plugin-pack.py").absolutePath,
            "build",
            "--definition", pluginDefinition.absolutePath,
            "--aar", aar.absolutePath,
            "--version", releaseVersion,
            "--output", lpg.absolutePath,
        )
    }
}
tasks.register("pluginArtifacts") {
    group = "distribution"
    description = ".lpg 패키지를 만들어요."
    dependsOn(pluginPackage)
}

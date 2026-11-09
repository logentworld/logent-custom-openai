// 테스트·릴리스가 같은 자산 스냅샷을 배포하도록 모듈 안에서 등록해요.
val repository = projectDir.parentFile
val releaseVersion = repository.resolve("VERSION").readText().trim()
require(Regex("[0-9]+[.][0-9]+[.][0-9]+").matches(releaseVersion)) { "VERSION은 x.y.z 형식으로 써 주세요." }
val distribution = repository.resolve("build/dist")
val contentSources = tasks.register<Zip>("pluginAssetSources") {
    dependsOn("stagePluginAssets")
    from(layout.buildDirectory.dir("staged/pluginAssets")) { into("assets") }
    from(repository.resolve("LICENSE"))
    from(repository.resolve("NOTICE"))
    from(repository.resolve("logent-plugin.json"))
    archiveFileName.set("logent-custom-openai-v${releaseVersion}-assets-source.zip")
    destinationDirectory.set(distribution)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val releaseAar = tasks.register<Copy>("pluginReleaseAar") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/aar/${project.name}-release.aar"))
    into(distribution)
    rename { "logent-custom-openai-v${releaseVersion}.aar" }
}
tasks.register("pluginArtifacts") {
    group = "distribution"
    description = "릴리스 AAR와 비설치용 자산 원본 ZIP을 만들어요."
    dependsOn(contentSources, releaseAar)
}

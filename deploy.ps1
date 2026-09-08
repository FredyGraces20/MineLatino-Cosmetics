# deploy.ps1 — Build, GitHub Release, update mods.json → launcher auto-sync
# Usage: .\deploy.ps1 [-SkipBuild] [-SkipGithub]
param([switch]$SkipBuild, [switch]$SkipGithub)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$version = '0.1.0-alpha.7'
$mcVersion = '1.21.4'
$repo = 'FredyGraces20/MineLatino-Cosmetics'
$tag = "v$version"

# ── 1. Build ──────────────────────────────────────────────────────────────
if (-not $SkipBuild) {
    Write-Host "`n== Building JARs ==" -ForegroundColor Cyan
    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
    $initScript = "$root\tools\isolated-test-output.gradle"
    & "$root\gradlew.bat" ":fabric:build" ":forge:build" "--init-script" $initScript
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
}

# ── 2. Locate JARs ────────────────────────────────────────────────────────
$buildBase = Get-ChildItem "C:\temp\cosmetics-build-*" -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $buildBase) {
    $buildBase = Get-Item "$root\fabric\build"
}
$fabricJar = Get-ChildItem "$($buildBase.FullName)" -Recurse -Filter "minelatino-cosmetics-fabric-$mcVersion-$version.jar" | Select-Object -First 1
$forgeJar  = Get-ChildItem "$($buildBase.FullName)" -Recurse -Filter "minelatino-cosmetics-forge-$mcVersion-$version.jar" | Select-Object -First 1
if (-not $fabricJar) {
    $fabricJar = Get-Item "$root\fabric\build\libs\minelatino-cosmetics-fabric-$mcVersion-$version.jar" -ErrorAction SilentlyContinue
}
if (-not $forgeJar) {
    $forgeJar = Get-Item "$root\forge\build\libs\minelatino-cosmetics-forge-$mcVersion-$version.jar" -ErrorAction SilentlyContinue
}
if (-not $fabricJar) { throw "Fabric JAR not found" }
Write-Host "Fabric: $($fabricJar.FullName) ($($fabricJar.Length) bytes)" -ForegroundColor Green
if ($forgeJar) { Write-Host "Forge:  $($forgeJar.FullName) ($($forgeJar.Length) bytes)" -ForegroundColor Green }

# ── 3. GitHub Release ─────────────────────────────────────────────────────
if (-not $SkipGithub) {
    Write-Host "`n== Creating GitHub Release $tag ==" -ForegroundColor Cyan
    # Delete existing release if any
    $existing = gh release view $tag --repo $repo 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "Deleting existing release $tag..." -ForegroundColor Yellow
        gh release delete $tag --repo $repo --yes --cleanup-tag
    }
    # Create release
    $releaseArgs = @('release', 'create', $tag, '--repo', $repo, '--title', "MineLatino Cosmetics $tag", '--notes', "Cosmetics mod $version for Minecraft $mcVersion")
    if ($fabricJar) { $releaseArgs += $fabricJar.FullName }
    if ($forgeJar) { $releaseArgs += $forgeJar.FullName }
    gh @releaseArgs
    if ($LASTEXITCODE -ne 0) { throw 'GitHub Release failed' }
    Write-Host "Release created: https://github.com/$repo/releases/tag/$tag" -ForegroundColor Green

    # ── 4. Update mods.json ───────────────────────────────────────────────
    Write-Host "`n== Updating mods.json ==" -ForegroundColor Cyan
    $fabricSha1 = (Get-FileHash $fabricJar.FullName -Algorithm SHA1).Hash.ToLower()
    $mods = @(
        @{
            id = 'minelatino-cosmetics'
            name = 'MineLatino Cosmetics'
            versions = @(
                @{
                    modVersion = $version
                    minecraftVersions = @($mcVersion)
                    loader = 'fabric'
                    downloadUrl = "https://github.com/$repo/releases/download/$tag/$($fabricJar.Name)"
                    sha1 = $fabricSha1
                    fileName = $fabricJar.Name
                    fileSize = $fabricJar.Length
                }
            )
        }
    )
    if ($forgeJar) {
        $forgeSha1 = (Get-FileHash $forgeJar.FullName -Algorithm SHA1).Hash.ToLower()
        $mods[0].versions += @{
            modVersion = $version
            minecraftVersions = @($mcVersion)
            loader = 'forge'
            downloadUrl = "https://github.com/$repo/releases/download/$tag/$($forgeJar.Name)"
            sha1 = $forgeSha1
            fileName = $forgeJar.Name
            fileSize = $forgeJar.Length
        }
    }
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText("$root\mods.json", (@($mods) | ConvertTo-Json -Depth 5 -Compress), $utf8NoBom)
    Write-Host "mods.json updated" -ForegroundColor Green

    # ── 5. Push mods.json ─────────────────────────────────────────────────
    Write-Host "`n== Pushing mods.json to GitHub ==" -ForegroundColor Cyan
    Push-Location $root
    try {
        git add mods.json
        git commit -m "chore: update mods.json to $tag"
        git push
        Write-Host "mods.json pushed" -ForegroundColor Green
    } finally { Pop-Location }
}

# ── 6. Local install ──────────────────────────────────────────────────────
$instanceMods = "E:\.minecraftx\instances\Minecraft 1.21.4 fabric\mods"
if (Test-Path $instanceMods) {
    Write-Host "`n== Installing to local instance ==" -ForegroundColor Cyan
    Get-ChildItem $instanceMods -Filter "minelatino-cosmetics-*.jar" | ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host "Removed old: $($_.Name)" -ForegroundColor Yellow
    }
    Copy-Item $fabricJar.FullName $instanceMods -Force
    Write-Host "Installed: $($fabricJar.Name)" -ForegroundColor Green
} else {
    Write-Host "Instance mods folder not found, skipping local install" -ForegroundColor Yellow
}

Write-Host "`n== Deploy complete ==" -ForegroundColor Cyan
Write-Host "Launcher will auto-sync the new version on next config refresh (~5 min)" -ForegroundColor Gray

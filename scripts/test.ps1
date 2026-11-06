# 本地跑单元测试（Windows / 中文路径专用）
#
# 为什么需要这个脚本：
#   Gradle 在 Windows 上跑单元测试时，会把测试进程的 classpath 写进一个临时参数文件
#   （gradle-worker-classpath*.txt），再由 java 启动器读回。这个文件里的路径一旦含中文，
#   java 启动器按系统 ANSI 码页（GBK）解出来就是乱码，于是报：
#       找不到或无法加载主类 worker.org.gradle.process.internal.worker.GradleWorkerMain
#   —— 看起来像"测试环境坏了"，其实只是路径编码问题。
#
# 这个脚本的做法：
#   在 %TEMP% 下建两个目录联接（junction，需管理员也可不用，普通用户即可）：
#       <TEMP>\gp  ->  <仓库>\手机隐私
#       <TEMP>\gh  ->  ...\_devtools\gradle-home
#   然后用 ASCII 路径去跑 gradle。全程不改任何源码，跑完自动删联接。
#
# 用法（在仓库根目录）：
#   powershell -ExecutionPolicy Bypass -File scripts\test.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\test.ps1 -Keep     # 保留联接便于复用
#
param(
    [switch]$Keep,
    [string]$Devtools = "D:\Desktop\作业\_devtools"
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot          # <...>\手机隐私\app（gradle 工程根）
$gradleHome = Join-Path $Devtools "gradle-home"

if (-not (Test-Path $gradleHome)) {
    Write-Host "找不到 gradle home: $gradleHome" -ForegroundColor Red
    exit 2
}

$j1 = Join-Path $env:TEMP "gp"
$j2 = Join-Path $env:TEMP "gh"
$log = Join-Path $env:TEMP "wall-test.log"

function New-Junction($link, $target) {
    if (Test-Path $link) { cmd /c "rmdir `"$link`"" | Out-Null }
    $out = cmd /c "mklink /J `"$link`" `"$target`"" 2>&1
    if (-not (Test-Path $link)) { throw "建联接失败：$out" }
}

New-Junction $j1 $repo
New-Junction $j2 $gradleHome

$env:JAVA_HOME          = Join-Path $Devtools "jdk17"
$env:ANDROID_HOME       = Join-Path $Devtools "android-sdk"
$env:ANDROID_USER_HOME  = Join-Path $Devtools "android-prefs"
$env:GRADLE_USER_HOME   = $j2
$env:GRADLE_OPTS        = "-Dorg.gradle.native=false"
$env:PATH               = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:PATH

$gradle = Join-Path $Devtools "gradle-8.14.3\bin\gradle.bat"

Write-Host "跑单元测试（走 ASCII 联接路径）…" -ForegroundColor Cyan
cmd /c "`"$gradle`" -p `"$j1`" testDebugUnitTest --no-daemon --console=plain > `"$log`" 2>&1"
$code = $LASTEXITCODE

# 汇总结果
$resDir = Join-Path $j1 "app\build\test-results\testDebugUnitTest"
$total = 0; $fail = 0
if (Test-Path $resDir) {
    Get-ChildItem $resDir -Filter *.xml | ForEach-Object {
        [xml]$x = Get-Content $_.FullName -Encoding utf8
        $total += [int]$x.testsuite.tests
        $fail  += [int]$x.testsuite.failures + [int]$x.testsuite.errors
    }
}

if ($code -eq 0) {
    Write-Host "测试通过：$total 条，失败 0 条" -ForegroundColor Green
} else {
    Write-Host "测试失败：$total 条中 $fail 条失败（退出码 $code）" -ForegroundColor Red
    Get-Content $log -Encoding utf8 | Select-String -Pattern "FAILED|expected|AssertionError" |
        Select-Object -First 20 | ForEach-Object { "  " + $_.Line }
    Write-Host "完整日志：$log"
}

if (-not $Keep) {
    foreach ($j in @($j1, $j2)) {
        if (Test-Path $j) { cmd /c "rmdir `"$j`"" | Out-Null }
    }
}

exit $code

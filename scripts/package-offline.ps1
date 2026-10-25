param([string]$Output = (Join-Path $PSScriptRoot '../target/offline-bundle'))
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'target/offline-face-api-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw '请先执行 mvn package' }
if (Test-Path -LiteralPath $Output) { throw '输出目录已存在，请用 -Output 指定新目录，避免将旧运行数据混入部署包' }
$models = @('face_detection_yunet_2023mar.onnx','face_recognition_sface_2021dec.onnx','face_recognition_auraface_v1.onnx')
foreach ($name in $models) { if (-not (Test-Path -LiteralPath (Join-Path $root "models/$name"))) { throw "缺少模型 $name" } }
New-Item -ItemType Directory -Force -Path $Output,(Join-Path $Output 'models'),(Join-Path $Output 'licenses'),(Join-Path $Output 'scripts') | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $Output 'offline-face-api-1.0.0.jar')
foreach ($name in $models) { Copy-Item -LiteralPath (Join-Path $root "models/$name") -Destination (Join-Path $Output 'models') }
Copy-Item -LiteralPath (Join-Path $root 'licenses/YuNet-MIT.txt'),(Join-Path $root 'licenses/SFace-Apache-2.0.txt'),(Join-Path $root 'licenses/AuraFace-Apache-2.0.txt') -Destination (Join-Path $Output 'licenses')
Copy-Item -LiteralPath (Join-Path $root 'README.md'),(Join-Path $root 'THIRD_PARTY_NOTICES.md') -Destination $Output
Copy-Item -LiteralPath (Join-Path $root 'src/main/resources/application.yml') -Destination $Output
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'migrate-faces.ps1'),(Join-Path $PSScriptRoot 'FaceHttpLoad.java') -Destination (Join-Path $Output 'scripts')
@'
param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try { & java -Xms512m -Xmx2g -jar offline-face-api-1.0.0.jar "--server.port=$Port" }
finally { Pop-Location }
'@ | Set-Content -LiteralPath (Join-Path $Output 'run.ps1') -Encoding utf8
@'
#!/bin/sh
set -eu
cd "$(dirname "$0")"
exec java -Xms512m -Xmx2g -jar offline-face-api-1.0.0.jar "$@"
'@ | Set-Content -LiteralPath (Join-Path $Output 'run.sh') -Encoding utf8NoBOM
$zip = Join-Path (Split-Path -Parent $Output) 'offline-face-api.zip'
Compress-Archive -LiteralPath $Output -DestinationPath $zip -Force
Write-Host "离线部署包: $zip"

param([string]$ModelDir = (Join-Path $PSScriptRoot '../models'))
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $ModelDir | Out-Null
$models = @(
    @{Dir='face_detection_yunet'; Name='face_detection_yunet_2023mar.onnx'; Hash='8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'},
    @{Dir='face_recognition_sface'; Name='face_recognition_sface_2021dec.onnx'; Hash='0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'},
    @{Name='face_recognition_auraface_v1.onnx'; Hash='a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60'; Url='https://huggingface.co/fal/AuraFace-v1/resolve/af6d057c9b0ec4071d4c49c80e3539258798b609/glintr100.onnx'}
)
foreach ($model in $models) {
    $path = Join-Path $ModelDir $model.Name
    if ((Test-Path -LiteralPath $path) -and ((Get-FileHash -LiteralPath $path).Hash -ieq $model.Hash)) { continue }
    $pending = "$path.download"
    try {
        $url = if ($model.Url) { $model.Url } else { "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/$($model.Dir)/$($model.Name)" }
        Invoke-WebRequest -Uri $url -OutFile $pending
        if ((Get-FileHash -LiteralPath $pending).Hash -ine $model.Hash) { throw "模型 SHA-256 校验失败: $($model.Name)" }
        Move-Item -LiteralPath $pending -Destination $path -Force
    } finally { if (Test-Path -LiteralPath $pending) { Remove-Item -LiteralPath $pending } }
    Write-Host "已准备 $($model.Name)"
}
Write-Host '模型准备完成；服务运行阶段无需联网。'

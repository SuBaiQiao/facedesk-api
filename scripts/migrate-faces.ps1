# 在新服务启动后显式执行。只读取旧库，使用原图重新提取，不复制旧特征。
param(
    [Parameter(Mandatory=$true)][string]$Source,
    [string]$BaseUrl = 'http://127.0.0.1:8080/api',
    [string]$Report = (Join-Path $PSScriptRoot '../target/migration-report.json')
)
$ErrorActionPreference = 'Stop'
if ([IO.Path]::GetFullPath($Source) -eq [IO.Path]::GetFullPath($Report)) { throw '报告路径不能与旧底库相同' }
Add-Type -AssemblyName System.Net.Http
$records = @(Get-Content -LiteralPath $Source -Raw | ConvertFrom-Json)
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromMinutes(2)
$results = @()
try {
    foreach ($record in $records) {
        $form = [System.Net.Http.MultipartFormDataContent]::new()
        $response = $null
        try {
            if ([string]::IsNullOrWhiteSpace($record.imageBase64)) { throw '记录没有原图，需要重新采集' }
            $image = [Convert]::FromBase64String(($record.imageBase64 -replace '^data:[^,]+,',''))
            $part = [System.Net.Http.ByteArrayContent]::new($image)
            $form.Add($part, 'img', 'face.jpg')
            $form.Add([System.Net.Http.StringContent]::new([string]$record.role), 'role')
            $form.Add([System.Net.Http.StringContent]::new([string]$record.code), 'code')
            if ($record.name) { $form.Add([System.Net.Http.StringContent]::new([string]$record.name), 'name') }
            $response = $client.PostAsync("$($BaseUrl.TrimEnd('/'))/face/v2/faceCollection", $form).GetAwaiter().GetResult()
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            if (-not $response.IsSuccessStatusCode -or $body.code -ne '200') { throw $body.msg }
            $results += [pscustomobject]@{role=$record.role; code=$record.code; success=$true; message='已重新提取'}
        } catch {
            $results += [pscustomobject]@{role=$record.role; code=$record.code; success=$false; message=$_.Exception.Message}
        } finally { if ($response) { $response.Dispose() }; $form.Dispose() }
    }
} finally { $client.Dispose() }
$parent = Split-Path -Parent $Report
if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
ConvertTo-Json -InputObject $results -Depth 4 | Set-Content -LiteralPath $Report -Encoding utf8
$failed = @($results | Where-Object { -not $_.success }).Count
Write-Host "迁移完成: 成功 $($results.Count - $failed)，失败 $failed，报告 $Report。旧文件未修改。"
if ($failed -gt 0) { exit 1 }

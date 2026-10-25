param(
    [Parameter(Mandatory=$true)][ValidateSet('mysql','dm')][string]$Type,
    [string]$Schema,
    [string]$ApiJar = (Join-Path $PSScriptRoot '../facedesk-api/target/facedesk-api-1.0.0.jar')
)
$ErrorActionPreference = 'Stop'
if (-not $Schema) { $Schema = if ($Type -eq 'dm') { 'FACEDESK' } else { 'facedesk' } }
if (-not (Test-Path -LiteralPath $ApiJar)) { $ApiJar = Join-Path $PSScriptRoot '../facedesk-api-1.0.0.jar' }
if (-not (Test-Path -LiteralPath $ApiJar)) { throw '请先构建服务，或用 -ApiJar 指定服务JAR' }
$schemaFile = Join-Path $PSScriptRoot "../facedesk-api/src/main/resources/db/$Type/schema.sql"
if (-not (Test-Path -LiteralPath $schemaFile)) { $schemaFile = Join-Path $PSScriptRoot "../db/$Type/schema.sql" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$driverFolder = Join-Path ([IO.Path]::GetTempPath()) ('facedesk-jdbc-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $driverFolder | Out-Null
$driverFile = Join-Path $driverFolder 'driver.jar'
try {
    $archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($ApiJar))
    try {
        $pattern = if ($Type -eq 'mysql') { '^BOOT-INF/lib/mysql-connector-j-' } else { '^BOOT-INF/lib/DmJdbcDriver18-' }
        $entry = $archive.Entries | Where-Object { $_.FullName -match $pattern } | Select-Object -First 1
        if (-not $entry) { throw '服务JAR未包含对应JDBC驱动' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $driverFile)
    } finally { $archive.Dispose() }
    & java -cp $driverFile (Join-Path $PSScriptRoot 'InitializeDatabase.java') $Type $Schema $schemaFile
    if ($LASTEXITCODE -ne 0) { throw '数据库初始化失败；DDL不保证回滚，请检查目标库，勿在有数据的库重复执行' }
} finally {
    if (Test-Path -LiteralPath $driverFile) { Remove-Item -LiteralPath $driverFile }
    if (Test-Path -LiteralPath $driverFolder) { Remove-Item -LiteralPath $driverFolder }
}

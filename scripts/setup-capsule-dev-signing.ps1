# One-time Capsule DEV signing setup on your OWN Windows PC. No keys committed to GitHub.
$ErrorActionPreference = 'Stop'
$keyDir = Join-Path $env:USERPROFILE 'CapsuleKeys'
$keyPath = Join-Path $keyDir 'capsule-dev.p12'
if (Test-Path -LiteralPath $keyPath) { throw 'A DEV key already exists. Never replace it: updates depend on this exact private key.' }
$java = Get-Command 'keytool.exe' -ErrorAction SilentlyContinue
if ($null -ne $java) { $keytool = $java.Source }
elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) { $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
else { $keytool = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe' }
if (-not (Test-Path -LiteralPath $keytool)) { throw 'Java keytool not found. Install Android Studio or set JAVA_HOME to a JDK.' }
[void](New-Item -ItemType Directory -Path $keyDir -Force)
$bytes = New-Object byte[] 28
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$password = [System.BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
$env:CAPSULE_DEV_STORE_PASSWORD = $password
$env:CAPSULE_DEV_KEY_PASSWORD = $password
try {
    & $keytool -genkeypair -noprompt -storetype PKCS12 -keystore $keyPath -storepass:env CAPSULE_DEV_STORE_PASSWORD -keypass:env CAPSULE_DEV_KEY_PASSWORD -alias capsule-dev -keyalg RSA -keysize 3072 -validity 10950 -dname 'CN=Capsule DEV, O=Capsule Music, C=US'
    if ($LASTEXITCODE -ne 0) { throw "keytool failed: $LASTEXITCODE" }
    Write-Host ''
    Write-Host "DEV KEY CREATED: $keyPath"
    Write-Host 'SAVE THE FOLLOWING PASSWORD IN YOUR LOCAL PASSWORD MANAGER:'
    Write-Host $password
    Write-Host ''
    Write-Host 'GitHub secrets: CAPSULE_DEV_STORE_PASSWORD and CAPSULE_DEV_KEY_PASSWORD = password above'
    Write-Host 'CAPSULE_DEV_KEY_ALIAS = capsule-dev'
    Write-Host 'CAPSULE_DEV_KEYSTORE_BASE64 = base64 of capsule-dev.p12'
    Write-Host ''
    Write-Host 'To copy BASE64 to clipboard, run this PowerShell command:'
    Write-Host ('[Convert]::ToBase64String([IO.File]::ReadAllBytes("' + $keyPath + '")) | Set-Clipboard')
    Write-Host 'Keep the .p12 only on your own PC, with a secure offline backup.'
} finally {
    Remove-Item Env:CAPSULE_DEV_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CAPSULE_DEV_KEY_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    [Array]::Clear($bytes, 0, $bytes.Length)
}

<#
  Creates your own CastApp release signing key on THIS computer.

  Run it in PowerShell from the folder where the key should live (somewhere you back up):
      powershell -ExecutionPolicy Bypass -File make-signing-key.ps1

  It writes three files and changes nothing else:
      castapp-release.p12          the key (PKCS#12). Back it up. Never commit or share it.
      castapp-release-base64.txt   the same key as text, to paste into the GitHub secret CASTAPP_KEYSTORE_BASE64
      castapp-release-KEEP-SAFE.txt the password and alias, and what to put into GitHub

  It needs OpenSSL (comes with Git for Windows) or keytool (comes with Android Studio or any Java JDK).
#>
$ErrorActionPreference = 'Stop'
$alias = 'castapp'
$p12 = Join-Path (Get-Location) 'castapp-release.p12'
$b64 = Join-Path (Get-Location) 'castapp-release-base64.txt'
$notes = Join-Path (Get-Location) 'castapp-release-KEEP-SAFE.txt'

if (Test-Path $p12) {
    Write-Host "There is already a key here: $p12" -ForegroundColor Yellow
    Write-Host 'Nothing was changed. Move or rename it first if you really want a new key.'
    exit 1
}

# A random password of 32 letters and digits (no symbols, so it pastes cleanly everywhere).
$chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$password = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

function Find-Tool($name, $candidates) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    return $null
}

$keytool = Find-Tool 'keytool' @(
    "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
    "$env:ProgramFiles\Android\Android Studio\jre\bin\keytool.exe",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
)
$openssl = Find-Tool 'openssl' @(
    "$env:ProgramFiles\Git\usr\bin\openssl.exe",
    "$env:ProgramFiles\Git\mingw64\bin\openssl.exe",
    "${env:ProgramFiles(x86)}\Git\usr\bin\openssl.exe"
)

$dname = 'CN=CastApp, O=CastApp'
if ($keytool) {
    Write-Host "Creating the key with keytool ($keytool) ..."
    & $keytool -genkeypair -v -storetype PKCS12 -keystore $p12 -alias $alias -keyalg RSA -keysize 4096 `
        -validity 10000 -dname $dname -storepass $password -keypass $password | Out-Null
} elseif ($openssl) {
    Write-Host "Creating the key with OpenSSL ($openssl) ..."
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("castapp-key-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    try {
        $key = Join-Path $tmp 'key.pem'; $cert = Join-Path $tmp 'cert.pem'
        & $openssl req -x509 -newkey rsa:4096 -sha256 -days 10000 -nodes -subj '/CN=CastApp/O=CastApp' `
            -keyout $key -out $cert 2>$null
        if ($LASTEXITCODE -ne 0) { throw 'OpenSSL could not create the key.' }
        # -legacy is only understood by OpenSSL 3; older ones already write the compatible format.
        $legacy = @()
        if ((& $openssl version) -match '^OpenSSL 3') { $legacy = @('-legacy') }
        & $openssl pkcs12 -export @legacy -inkey $key -in $cert -name $alias -out $p12 -passout "pass:$password"
        if ($LASTEXITCODE -ne 0) { throw 'OpenSSL could not write the key file.' }
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
} else {
    Write-Host 'Neither keytool nor OpenSSL was found.' -ForegroundColor Red
    Write-Host 'Install Git for Windows (https://git-scm.com) or Android Studio, then run this again.'
    exit 1
}

[Convert]::ToBase64String([System.IO.File]::ReadAllBytes($p12)) | Set-Content -NoNewline -Encoding ascii $b64

@"
CastApp release signing key - KEEP THIS FILE AND castapp-release.p12 SAFE (and backed up).
If you lose them, updates can no longer be installed over the app.

Key file:   castapp-release.p12
Alias:      $alias
Password:   $password   (the same for the key file and the key)

Put these into GitHub: your repository -> Settings -> Secrets and variables -> Actions -> New repository secret
  CASTAPP_KEYSTORE_BASE64    = the whole content of castapp-release-base64.txt
  CASTAPP_KEYSTORE_PASSWORD  = $password
  CASTAPP_KEY_ALIAS          = $alias
(CASTAPP_KEY_PASSWORD is not needed: it is the same password.)

After that, every new release is signed with this key. Builds signed with the old test key can't be
updated by it: uninstall CastApp once on each device before installing the first build with your key.
"@ | Set-Content -Encoding utf8 $notes

Write-Host ''
Write-Host 'Done. Three files were created here:' -ForegroundColor Green
Write-Host "  $p12"
Write-Host "  $b64"
Write-Host "  $notes   <- open this one next: it says what to put into GitHub"

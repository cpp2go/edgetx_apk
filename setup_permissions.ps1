<#
.SYNOPSIS
    Grants every permission this app can be given without a dialog, once, after the APK is
    installed - so the prompts that come back after every boot stop coming back.

.DESCRIPTION
    Run this once after installing the APK. It needs adb only: no root and no system app.
    Everything it grants is stored by Android and survives a reboot, which is what makes the
    difference to tapping "allow" in a dialog that the app itself re-raises on every launch.

    What it grants:
      * ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION  - the remote's own GPS (RcGps)
      * WRITE_EXTERNAL_STORAGE                         - the card on Android 10 (RC Pro, RC Plus)
      * MANAGE_EXTERNAL_STORAGE, as an app-op          - "All files access" on Android 11+
                                                         (RC Plus 2), which has no dialog at all
      * READ_LOGS                                      - the remote's own button log (RcDpadLog)

    What it cannot grant: the USB device permission (the DJI Virtual Joystick and the RF
    module's serial port). Android hands that one out only through a dialog, or to a system
    app, and this app is neither. The dialog offers "use by default for this USB device"
    because the app declares those devices in res/xml/usb_device_filter.xml; ticked once, the
    system grants them at boot with no dialog at all. The app says so on screen once
    (EdgeTxApplication.hintUsbDefaultOnce).

.PARAMETER Package
    The application id. Default: com.edgetx.droidui.

.PARAMETER Apk
    Optional APK to install first, with -r -g (which also grants the runtime permissions).

.PARAMETER Restart
    Force-stop the app afterwards, so the next launch starts with every grant in place.

.EXAMPLE
    .\build\setup_permissions.ps1

.EXAMPLE
    .\build\setup_permissions.ps1 -Apk app\build\outputs\apk\release\app-release.apk -Restart
#>
[CmdletBinding()]
param(
    [string]$Package = 'com.edgetx.droidui',
    [string]$Apk,
    [switch]$Restart
)

# adb writes its session banner on stderr, which a 'Stop' preference would turn into a fatal
# error. What is fatal here is said with throw, and every adb answer is checked by hand.
$ErrorActionPreference = 'Continue'

function Find-Adb {
    # -CommandType Application: without it, "adb" also matches this script's own Invoke-Adb
    # function, which has no Source and would be handed back as an empty path.
    $command = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($command -and $command.Source) { return $command.Source }

    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }

    $local = Join-Path (Split-Path $PSScriptRoot -Parent) 'local.properties'
    if (Test-Path $local) {
        $line = Get-Content $local | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $dir = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\([:\\])', '$1'
            $candidates += (Join-Path $dir 'platform-tools\adb.exe')
        }
    }

    foreach ($path in $candidates) {
        if ($path -and (Test-Path $path)) { return $path }
    }
    throw 'adb was not found: put platform-tools on PATH, or set ANDROID_HOME.'
}

# One adb call; the arguments are an array so that -r / -g are not read as parameters here.
function Invoke-Adb {
    param([Parameter(Mandatory = $true)][string[]]$a)
    $text = & $script:adb @a 2>&1 | ForEach-Object { $_.ToString() }
    ($text | Out-String).Trim()
}

$adb = Find-Adb
Write-Host "adb : $adb"

$devices = (Invoke-Adb @('devices')) -split "`r?`n" | Where-Object { $_ -match '\sdevice$' }
if (-not $devices) {
    throw 'No device: connect the RC over adb (adb devices) and run this again.'
}
Write-Host ("dev : " + ($devices -join ', '))
$api = [int](Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk'))
Write-Host "api : $api"

if ($Apk) {
    if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
    Write-Host "inst: $Apk"
    Write-Host ("      " + (Invoke-Adb @('install', '-r', '-g', $Apk)))
}

$installed = Invoke-Adb @('shell', 'pm', 'list', 'packages', $Package)
if ($installed -notmatch [regex]::Escape($Package)) {
    throw "$Package is not installed: install the APK first, or pass -Apk <path>."
}

Write-Host ''
Write-Host 'granting'
$grants = @(
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.WRITE_EXTERNAL_STORAGE',
    'android.permission.READ_LOGS'
)
foreach ($permission in $grants) {
    $text = Invoke-Adb @('shell', 'pm', 'grant', $Package, $permission)
    if ($text) {
        # Expected on a version that does not have the permission (WRITE_EXTERNAL_STORAGE above
        # Android 10 falls in here) and for one this build is not allowed to hand out.
        Write-Host ("  grant {0,-46} {1}" -f $permission, ($text -replace "`r?`n", ' '))
    } else {
        Write-Host ("  grant {0,-46} ok" -f $permission)
    }
}

if ($api -ge 30) {
    $text = Invoke-Adb @('shell', 'appops', 'set', $Package, 'MANAGE_EXTERNAL_STORAGE', 'allow')
    Write-Host ("  appop {0,-46} {1}" -f 'MANAGE_EXTERNAL_STORAGE', $(if ($text) { $text } else { 'ok' }))
}

Write-Host ''
Write-Host 'verifying'
$dump = Invoke-Adb @('shell', 'dumpsys', 'package', $Package)
$checks = @(
    'ACCESS_FINE_LOCATION',
    'ACCESS_COARSE_LOCATION',
    'READ_LOGS',
    'WRITE_EXTERNAL_STORAGE'
)
foreach ($permission in $checks) {
    $granted = $dump -match ($permission + ': granted=true')
    Write-Host ("  {0,-30} {1}" -f $permission, $(if ($granted) { 'granted' } else { 'not granted' }))
}

if ($api -ge 30) {
    $all = Invoke-Adb @('shell', 'appops', 'get', $Package, 'MANAGE_EXTERNAL_STORAGE')
    Write-Host ("  {0,-30} {1}" -f 'MANAGE_EXTERNAL_STORAGE', $(if ($all -match 'allow') { 'allowed' } else { 'not allowed' }))
}

if ($Restart) {
    Write-Host ''
    Write-Host 'restarting the app'
    Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
    Invoke-Adb @('shell', 'am', 'start', '-n', "$Package/.EdgeTxActivity") | Out-Null
}

Write-Host ''
Write-Host 'remaining, and this one needs a tap once:'
Write-Host '  USB device permission - allow the dialog when the app raises it and tick'
Write-Host '  "use by default for this USB device". Android keeps that choice, not the grant,'
Write-Host '  so from the next boot the joystick and the module are handed over silently.'


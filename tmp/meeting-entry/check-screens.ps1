$ErrorActionPreference = 'Stop'
$adbPath = 'C:\Users\Akash\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$captureDir = 'D:\Projects\VexaMeet\tmp\meeting-entry'

function Invoke-Device {
    $ErrorActionPreference = 'Continue'
    & $adbPath -s emulator-5554 @args
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $args" }
}

function Read-Ui {
    for ($retry = 0; $retry -lt 5; $retry++) {
        $dump = Invoke-Device shell uiautomator dump --compressed /sdcard/vexa-check.xml 2>&1
        if ($dump -match 'dumped to') { break }
        Start-Sleep -Seconds 2
    }
    Invoke-Device pull /sdcard/vexa-check.xml "$captureDir\current.xml" | Out-Null
    [xml](Get-Content "$captureDir\current.xml" -Raw)
}

function Tap-Node([string]$query) {
    $document = Read-Ui
    $node = $document.SelectSingleNode($query)
    if ($null -eq $node) { throw "UI element not found: $query" }
    $bounds = [regex]::Matches($node.bounds, '\d+')
    $tapX = [int](([int]$bounds[0].Value + [int]$bounds[2].Value) / 2)
    $tapY = [int](([int]$bounds[1].Value + [int]$bounds[3].Value) / 2)
    Invoke-Device shell input tap $tapX $tapY | Out-Null
    Start-Sleep -Seconds 1
}

function Capture-Screen([string]$name) {
    Invoke-Device shell screencap -p /sdcard/vexa-capture.png | Out-Null
    Invoke-Device pull /sdcard/vexa-capture.png "$captureDir\$name.png" | Out-Null
    Copy-Item "$captureDir\current.xml" "$captureDir\$name.xml" -Force
    Write-Output "Captured $name"
}

Invoke-Device install -r 'D:\Projects\VexaMeet\app\build\outputs\apk\debug\app-debug.apk'
Invoke-Device shell input keyevent KEYCODE_WAKEUP | Out-Null
Invoke-Device shell wm dismiss-keyguard | Out-Null
Invoke-Device shell am force-stop com.vexa.meet | Out-Null
Invoke-Device shell am start -n com.vexa.meet/.MainActivity | Out-Null
Start-Sleep -Seconds 4
$document = Read-Ui
if ($null -eq $document.SelectSingleNode('//node[@text="Secure. Simple. Together."]')) { throw 'Welcome screen missing' }
Capture-Screen 'home-final'
Tap-Node '//node[@text="I already have a room ID"]'
Start-Sleep -Seconds 1
$document = Read-Ui
if ($null -eq $document.SelectSingleNode('//node[@text="Join a Meeting"]')) { throw 'Join screen missing' }
Capture-Screen 'join-final'
Tap-Node '//node[@class="android.widget.EditText"]'
Invoke-Device shell input text room_keyboard_test | Out-Null
Start-Sleep -Seconds 1
$document = Read-Ui
Capture-Screen 'join-keyboard'
Invoke-Device shell input keyevent 4 | Out-Null
Tap-Node '//node[@text="Back"]'
Tap-Node '//node[@text="Start a Meeting"]'
Start-Sleep -Seconds 1

for ($attempt = 0; $attempt -lt 5; $attempt++) {
    $document = Read-Ui
    $allowButton = $document.SelectSingleNode('//node[contains(@resource-id,"permission_allow_foreground_only_button") or contains(@resource-id,"permission_allow_button")]')
    if ($null -eq $allowButton) { break }
    Tap-Node '//node[contains(@resource-id,"permission_allow_foreground_only_button") or contains(@resource-id,"permission_allow_button")]'
    Start-Sleep -Seconds 1
}

Start-Sleep -Seconds 2
# Bring call controls back if their normal auto-hide timer has fired.
Invoke-Device shell input tap 540 900 | Out-Null
$document = Read-Ui
if ($null -eq $document.SelectSingleNode('//node[@resource-id="com.vexa.meet:id/btnDisconnect"]')) {
    Invoke-Device shell input tap 540 900 | Out-Null
    $document = Read-Ui
}
Capture-Screen 'call-started'
$endCallNode = $document.SelectSingleNode('//node[@resource-id="com.vexa.meet:id/btnDisconnect"]')
if ($null -eq $endCallNode) { throw 'End-call control missing' }
$endBounds = [regex]::Matches($endCallNode.bounds, '\d+')
$endX = [int](([int]$endBounds[0].Value + [int]$endBounds[2].Value) / 2)
$endY = [int](([int]$endBounds[1].Value + [int]$endBounds[3].Value) / 2)
Start-Sleep -Seconds 4
Invoke-Device shell input tap 540 900 | Out-Null
Start-Sleep -Milliseconds 400
Invoke-Device shell input tap $endX $endY | Out-Null
Start-Sleep -Seconds 1
$document = Read-Ui
if ($null -eq $document.SelectSingleNode('//node[@text="Secure. Simple. Together."]')) { throw 'Call end did not return to welcome' }
Capture-Screen 'after-call'
Write-Output 'PASS: Welcome, join, keyboard, permission-to-call flow, and return after ending call.'

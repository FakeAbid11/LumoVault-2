param([string]$ProjectRoot)

$ktRoot = Join-Path $ProjectRoot 'app\src\main\kotlin'
$resRoot = Join-Path $ProjectRoot 'app\src\main\res\values'

# Every string name defined in any res/values/*.xml
$defined = @{}
Get-ChildItem -Path $resRoot -Filter *.xml | ForEach-Object {
    $t = [System.IO.File]::ReadAllText($_.FullName)
    foreach ($m in [regex]::Matches($t, 'name="([^"]+)"')) {
        $defined[$m.Groups[1].Value] = $true
    }
}
Write-Output ("defined string names: " + $defined.Count)

# Every R.string.X reference in Kotlin
$referenced = @{}
Get-ChildItem -Path $ktRoot -Recurse -Filter *.kt | ForEach-Object {
    $file = $_.FullName
    $t = [System.IO.File]::ReadAllText($file)
    foreach ($m in [regex]::Matches($t, 'R\.string\.([A-Za-z0-9_]+)')) {
        $name = $m.Groups[1].Value
        if (-not $referenced.ContainsKey($name)) { $referenced[$name] = @() }
        $referenced[$name] += $file.Split('\')[-1]
    }
}
Write-Output ("referenced string names: " + $referenced.Count)

# Report references with no definition
$missing = 0
foreach ($name in $referenced.Keys) {
    if (-not $defined.ContainsKey($name)) {
        $missing++
        Write-Output ("MISSING R.string." + $name + " used in " + (($referenced[$name] | Sort-Object -Unique) -join ', '))
    }
}
Write-Output ("missing total: " + $missing)

# Duplicate definitions across files (would fail resource merging)
$seen = @{}
Get-ChildItem -Path $resRoot -Filter *.xml | ForEach-Object {
    $file = $_.Name
    $t = [System.IO.File]::ReadAllText($_.FullName)
    foreach ($m in [regex]::Matches($t, 'name="([^"]+)"')) {
        $n = $m.Groups[1].Value
        if ($seen.ContainsKey($n)) {
            Write-Output ("DUPLICATE " + $n + " in " + $seen[$n] + " and " + $file)
        } else {
            $seen[$n] = $file
        }
    }
}
Write-Output "string audit complete"
param([string]$ProjectRoot)

$ktRoot = Join-Path $ProjectRoot 'app\src\main\kotlin'
$byPackage = @{}

Get-ChildItem -Path $ktRoot -Recurse -Filter *.kt | ForEach-Object {
    $file = $_
    $text = [System.IO.File]::ReadAllText($file.FullName)
    $pkgMatch = [regex]::Match($text, '(?m)^package\s+([A-Za-z0-9_.]+)')
    if (-not $pkgMatch.Success) { return }
    $pkg = $pkgMatch.Groups[1].Value

    # Top-level declarations only: column 0, no leading whitespace.
    $names = New-Object System.Collections.ArrayList
    foreach ($m in [regex]::Matches($text, '(?m)^(fun|class|object|interface|val|var|enum class)\s+([A-Za-z0-9_]+)')) {
        [void]$names.Add($m.Groups[2].Value)
    }
    foreach ($n in $names) {
        $key = "$pkg::$n"
        if (-not $byPackage.ContainsKey($key)) { $byPackage[$key] = @() }
        $byPackage[$key] += $file.Name
    }
}

$dupes = 0
foreach ($key in $byPackage.Keys) {
    $files = $byPackage[$key] | Sort-Object -Unique
    if ($files.Count -gt 1) {
        $dupes++
        Write-Output ("DUPLICATE DECL " + $key + " in " + ($files -join ', '))
    }
}
Write-Output ("duplicate declarations: " + $dupes)
Write-Output ("total top-level declarations: " + $byPackage.Count)
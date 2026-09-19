param([string]$Root)
Get-ChildItem -Path $Root -Recurse -Filter *.kt | ForEach-Object {
    $t = [System.IO.File]::ReadAllText($_.FullName)
    $d = 0
    $p = 0
    foreach ($ch in $t.ToCharArray()) {
        if ($ch -eq '{') { $d++ } elseif ($ch -eq '}') { $d-- }
        elseif ($ch -eq '(') { $p++ } elseif ($ch -eq ')') { $p-- }
    }
    if ($d -ne 0 -or $p -ne 0) {
        Write-Output ("UNBALANCED " + $_.Name + " braces=$d parens=$p")
    }
}
Write-Output "brace scan complete"
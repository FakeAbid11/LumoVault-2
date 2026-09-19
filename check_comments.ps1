param([string]$Root)
Get-ChildItem -Path $Root -Recurse -Filter *.kt | ForEach-Object {
    $t = [System.IO.File]::ReadAllText($_.FullName)
    $open = ([regex]::Matches($t, '/\*')).Count
    $close = ([regex]::Matches($t, '\*/')).Count
    if ($open -ne $close) {
        Write-Output ("MISMATCH " + $_.FullName + " open=$open close=$close")
    }
}
Write-Output "scan complete"
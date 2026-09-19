$repo = 'FakeAbid11/LumoVault-2'
$id = (gh run list --repo $repo --limit 1 --json databaseId | ConvertFrom-Json).databaseId
gh run watch $id --repo $repo --exit-status --interval 15 | Out-Null
$code = $LASTEXITCODE
gh run view $id --repo $repo --json conclusion,status | Set-Content ci_result.json
exit $code

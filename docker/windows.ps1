param(
    [string]$File1,
    [string]$File2
)

# Calculate the timestamp for one month from now
$mergeTimestamp = ((Get-Date).AddSeconds(60).ToUniversalTime() - (Get-Date "1970-01-01")).TotalSeconds

# Function to update JSON files
function Update-Timestamps {
    param (
        [string]$FilePath,
        [int]$NewTimestamp
    )
    if (-Not (Test-Path $FilePath)) {
        Write-Host "File $FilePath does not exist." -ForegroundColor Red
        return
    }

    # Read file content and replace timestamps
    $content = Get-Content $FilePath
    $updatedContent = $content -replace '(?<=^\s*"shanghaiTime": ).*', "$NewTimestamp,"
    $updatedContent = $updatedContent -replace '(?<=^\s*"cancunTime": ).*', "$NewTimestamp,"

    # Write updated content back to the file
    $updatedContent | Set-Content $FilePath
    Write-Host "Updated timestamps in file: $FilePath" -ForegroundColor Green
}

# Update both files
Update-Timestamps -FilePath docker/genesis-besu.json -NewTimestamp $mergeTimestamp
Update-Timestamps -FilePath docker/genesis-geth.json -NewTimestamp $mergeTimestamp

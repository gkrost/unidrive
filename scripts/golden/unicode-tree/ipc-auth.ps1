<#
.SYNOPSIS
    Connect-UnidriveIpc: opens a profile's daemon IPC socket and authenticates (IPC protocol 2), for the probes in this folder.

.DESCRIPTION
    Dot-source this file. The handshake is specified in docs/dev/specs/ipc-authentication.md: the token of the requested scope is
    read from the profile's config folder at every connect (a restarted daemon has new tokens), the client sends hello and
    hello.proof, and the daemon's proof is checked before the connection is used. Any failure throws; nothing is retried.
    Without a token file the daemon must be a protocol-1 daemon (daemon.status says protocol_version 1): the connection is then
    used without authentication, with one warning. That fallback is temporary.
    The probes only read, so they ask for the read scope. Needs PowerShell 7 at run time (AF_UNIX sockets).
#>

$script:UnidriveIpcUtf8 = [System.Text.UTF8Encoding]::new($false)
$script:UnidriveIpcProtocol1Warned = $false

function ConvertTo-UnidriveBase64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# The bytes of an unpadded base64url text, or $null unless the text is exactly their encoding.
function ConvertFrom-UnidriveBase64Url([string]$Text) {
    if ($Text -cnotmatch '^[A-Za-z0-9_-]+$') { return $null }
    $t = $Text.Replace('-', '+').Replace('_', '/')
    switch ($t.Length % 4) { 1 { return $null } 2 { $t += '==' } 3 { $t += '=' } }
    try { $bytes = [Convert]::FromBase64String($t) } catch { return $null }
    if ((ConvertTo-UnidriveBase64Url $bytes) -cne $Text) { return $null }
    , $bytes
}

# HMAC-SHA256 over the length-prefixed message of the spec; Side is 'client' or 'server' (the server proof also binds the client proof).
function Get-UnidriveIpcProof([byte[]]$Key, [string]$Side, [string]$Scope, [string]$Profile, [string]$ClientNonce, [string]$ServerNonce, [string]$ClientProof) {
    $message = "unidrive-ipc-v2|$Side|$Scope|$($script:UnidriveIpcUtf8.GetByteCount($Profile)):$Profile|$ClientNonce|$ServerNonce"
    if ($Side -eq 'server') { $message += "|$ClientProof" }
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($Key)
    try { ConvertTo-UnidriveBase64Url $hmac.ComputeHash($script:UnidriveIpcUtf8.GetBytes($message)) } finally { $hmac.Dispose() }
}

# Constant-time comparison of two proofs (base64url texts).
function Test-UnidriveIpcProof([string]$Expected, [string]$Actual) {
    if ($null -eq $Actual -or $Expected.Length -ne $Actual.Length) { return $false }
    $diff = 0
    for ($i = 0; $i -lt $Expected.Length; $i++) { $diff = $diff -bor ([int][char]$Expected[$i] -bxor [int][char]$Actual[$i]) }
    return ($diff -eq 0)
}

# The profile's config folder, resolved like the engine does: UNIDRIVE_CONFIG_DIR, ~/.config/unidrive when it has a config.toml,
# %APPDATA%\unidrive, else ~/.config/unidrive.
function Get-UnidriveProfileDir([string]$Profile, [string]$ConfigDir) {
    if (-not $ConfigDir) {
        $xdg = Join-Path (Join-Path $HOME '.config') 'unidrive'
        if ($env:UNIDRIVE_CONFIG_DIR) { $ConfigDir = $env:UNIDRIVE_CONFIG_DIR }
        elseif (Test-Path -LiteralPath (Join-Path $xdg 'config.toml')) { $ConfigDir = $xdg }
        elseif ($env:APPDATA) { $ConfigDir = Join-Path $env:APPDATA 'unidrive' }
        else { $ConfigDir = $xdg }
    }
    Join-Path $ConfigDir $Profile
}

function Connect-UnidriveIpc {
    param(
        [Parameter(Mandatory)][string]$Profile,
        [ValidateSet('read', 'full')][string]$Scope = 'read',
        [string]$ConfigDir,
        [string]$SocketPath = (Join-Path $env:TEMP "unidrive-ipc\unidrive-$Profile.sock"),
        [string]$Client = 'unidrive-probe',
        [int]$TimeoutMs = 5000
    )
    $tokenName = if ($Scope -eq 'full') { 'ipc.token' } else { 'ipc.read.token' }
    $tokenFile = Join-Path (Get-UnidriveProfileDir $Profile $ConfigDir) $tokenName
    $key = $null
    if (Test-Path -LiteralPath $tokenFile) {
        $key = ConvertFrom-UnidriveBase64Url ([IO.File]::ReadAllText($tokenFile).Trim())
        if ($null -eq $key -or $key.Length -ne 32) { throw "the IPC token file $tokenFile is not a valid token" }
    }
    $socket = [System.Net.Sockets.Socket]::new([System.Net.Sockets.AddressFamily]::Unix, [System.Net.Sockets.SocketType]::Stream, [System.Net.Sockets.ProtocolType]::Unspecified)
    try {
        $socket.Connect([System.Net.Sockets.UnixDomainSocketEndPoint]::new($SocketPath))
        $socket.ReceiveTimeout = $TimeoutMs
        $stream = [System.Net.Sockets.NetworkStream]::new($socket)
        $reader = [IO.StreamReader]::new($stream, $script:UnidriveIpcUtf8)
        $ask = {
            param([string]$line)
            $bytes = $script:UnidriveIpcUtf8.GetBytes($line + "`n"); $stream.Write($bytes, 0, $bytes.Length); $stream.Flush()
            $reply = $reader.ReadLine()
            if ($null -eq $reply) { throw 'the daemon closed the connection during the IPC handshake' }
            $reply | ConvertFrom-Json
        }
        if ($null -eq $key) {
            $status = & $ask '{"verb":"daemon.status"}'
            $version = if ($null -ne $status.protocol_version) { [int]$status.protocol_version } else { 1 }
            if ($version -ge 2) { throw "cannot authenticate to this daemon (IPC protocol $version): no IPC token at $tokenFile" }
            if (-not $script:UnidriveIpcProtocol1Warned) {
                Write-Warning "no IPC token at $tokenFile and the daemon speaks IPC protocol ${version}: connecting without authentication"
                $script:UnidriveIpcProtocol1Warned = $true
            }
        } else {
            $nonceBytes = [byte[]]::new(16)
            $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
            try { $rng.GetBytes($nonceBytes) } finally { $rng.Dispose() }
            $clientNonce = ConvertTo-UnidriveBase64Url $nonceBytes
            $hello = [ordered]@{ verb = 'hello'; protocol = 2; scope = $Scope; client = $Client; nonce = $clientNonce } | ConvertTo-Json -Compress
            $step1 = & $ask $hello
            $serverNonceBytes = if ($step1.snonce -is [string]) { ConvertFrom-UnidriveBase64Url $step1.snonce } else { $null }
            if (-not ($step1.ok -eq $true -and $step1.step -eq 1 -and $null -ne $serverNonceBytes -and $serverNonceBytes.Length -eq 16)) {
                if ($step1.error -eq 'unknown_verb') { throw "the daemon does not support IPC authentication, but $tokenFile exists; restart the daemon" }
                throw "the daemon rejected the IPC handshake ($($step1.error))"
            }
            $proof = Get-UnidriveIpcProof $key 'client' $Scope $Profile $clientNonce $step1.snonce
            $step2 = & $ask ('{"verb":"hello.proof","proof":"' + $proof + '"}')
            if ($step2.ok -ne $true) { throw "the daemon rejected the IPC token ($($step2.error)); restart the daemon or this probe" }
            $expected = Get-UnidriveIpcProof $key 'server' $Scope $Profile $clientNonce $step1.snonce $proof
            if ($step2.scope -cne $Scope -or -not (Test-UnidriveIpcProof $expected ([string]$step2.proof))) {
                throw "the daemon's proof does not match the IPC token; the socket may not belong to the unidrive daemon of profile $Profile"
            }
        }
        $socket.ReceiveTimeout = 0
        [pscustomobject]@{ Socket = $socket; Stream = $stream; Reader = $reader; Authenticated = ($null -ne $key) }
    } catch {
        $socket.Dispose()
        throw
    }
}

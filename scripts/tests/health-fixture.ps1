param([int]$Port)
$ErrorActionPreference = 'Stop'
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
try {
    $listener.Start()
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $bytes = [System.Text.Encoding]::UTF8.GetBytes('{"status":"UP"}')
        $context.Response.ContentType = 'application/json'
        $context.Response.ContentLength64 = $bytes.Length
        $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
        $context.Response.Close()
    }
} finally { $listener.Close() }

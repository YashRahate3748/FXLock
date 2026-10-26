$ErrorActionPreference='Stop'
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
 Where-Object LocalPort -in 2181,9092,8761,8081,8082,8083,8084 |
 Select-Object LocalAddress,LocalPort,OwningProcess
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
 Where-Object {$_.CommandLine -match 'kafka.Kafka|QuorumPeerMain'} |
 Select-Object ProcessId,ExecutablePath,CommandLine
# The process command line shows the installation's classpath and config path.
# Do not terminate either installation until you identify its process and data directory.

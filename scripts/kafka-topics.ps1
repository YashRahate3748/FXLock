param([string]$KafkaHome='C:\kafka_2.12-2.2.0',[string]$ZooKeeper='localhost:2181')
$ErrorActionPreference='Stop'
$topics=@('fx-rate-updated','fx-settlement-completed','fx-system-alert','fx-quote-events','fx-simulated-funding-completed')
foreach($topic in $topics) {
 & "$KafkaHome\bin\windows\kafka-topics.bat" --zookeeper $ZooKeeper --create --if-not-exists --replication-factor 1 --partitions 1 --topic $topic
 if($LASTEXITCODE -ne 0) {throw "Topic creation failed: $topic"}
}
& "$KafkaHome\bin\windows\kafka-topics.bat" --zookeeper $ZooKeeper --list

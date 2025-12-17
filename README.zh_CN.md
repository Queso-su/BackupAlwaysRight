<img width="200" height="200" alt="remove-photos-background-removed" src="https://github.com/user-attachments/assets/232b8c7b-a983-470e-9cc7-f8b2979b6998" />

# 备份永远是对的

- **简体中文**
- [English](https://github.com/Queso-su/BackupAlwaysRight/blob/master/README.md)


一个功能强大的Minecraft服务器备份模组，支持智能备份、多语言和多存储路径。  

目前仅支持
Minecraft 1.21.1 - [1.21.11]() **Fabric** 平台 
##  功能特性

- **定时备份** - 支持分钟、小时、天为单位设置备份间隔
- **智能备份** - 仅在世界发生显著变化时创建备份
- **多路径支持** - 支持同时备份到多个本地或网络路径
- **手动控制** - 提供完整的命令系统控制备份流程
- **多语言支持** - 支持英文、简体中文，自动检测系统语言
- **备份验证** - 自动验证备份文件完整性
- **压缩控制** - 支持0-9级压缩比例调节
- **旧备份清理** - 自动清理超出数量限制的旧备份

##  安装方法

1. 将模组文件放入服务器的 `mods` 文件夹
2. 启动服务器生成配置文件
3. 根据需要修改配置文件 `config/bar.conf`
4. 使用 `/bar reload` 命令重新加载配置或重启服务器

##  命令列表

| 命令 | 描述 | 权限等级 |
|------|------|----------|
| `/bar start` | 立即开始手动备份 | 2 |
| `/bar list` | 列出所有备份文件 | 0 |
| `/bar status` | 显示备份系统状态 | 0 |
| `/bar reload` | 重新加载配置文件 | 3 |
| `/bar interval minute <1-1440>` | 设置分钟间隔 | 3 |
| `/bar interval hour <1-24>` | 设置小时间隔 | 3 |
| `/bar interval day <1-30>` | 设置天间隔 | 3 |
| `/bar autobackup enable/disable` | 启用/禁用自动备份 | 3 |
| `/bar debug enable/disable` | 启用/禁用调试模式 | 3 |
| `/bar shutdown` | 备份并关闭服务器 | 4 |
| `/bar shutdown delay <1-60>` | 设置关机延迟时间 | 4 |

##  配置说明

编辑 `config/bar.conf` 文件：

###  路径示例

```properties

# 要备份的文件夹（多个用;分隔）
# Example :  world;world_nether;world_the_end;mods;whatelse
backupFolders=world  


# 本地路径
backupPaths=./backups;backups;/home/mcserver/backups


# 网络路径
backupPaths=backups;\\192.168.0.105\Base\Backups;D:\Backups
```

###  常规设置  


```properties

# 语言设置 (auto, en_us, zh_cn)
language=zh_cn


# 要备份的文件夹（多个用;分隔）
backupFolders=world


# 是否启用自动备份
backupEnabled=true


# 备份保存路径（多个用;分隔）
backupPaths=backups


# 自动备份间隔（支持单位：m=分钟，h=小时，d=天）
backupInterval=30m


# 最大备份数量
maxBackups=20


# 备份时是否通知玩家
notifyPlayers=false


# 备份前提示时间（秒）
noticeTime=3


# 备份完成后关闭服务器延迟（秒）
shutdownDelay=15
```

###  高级设置  

```properties

# 是否验证备份完整性
verifyBackup=true


# 是否智能备份（无变化时跳过）
smartBackup=true


# 是否需要显著变化
requireSignificantChange=false


# 变化阈值（0-1.0）
changeThreshold=0.01


# 最小变化文件数
minChangedFiles=5


# 压缩级别（0-9）
compressionLevel=3


# 最小备份大小（MB）
minBackupSizeMB=5


# 调试模式
debugMode=false

```


###  故障排除

- 启用调试模式：/bar debug enable

- 检查路径权限和磁盘空间

- 备份速度慢时降低压缩级别

- 磁盘空间不足时减少 maxBackups 值

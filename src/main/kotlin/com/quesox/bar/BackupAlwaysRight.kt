package com.quesox.bar

import net.fabricmc.api.ModInitializer


object BackupAlwaysRight : ModInitializer {
	//private val logger = LoggerFactory.getLogger("backupalwaysright")

	override fun onInitialize() {


		// 初始化备份管理器
		BackupManager.initialize()

		// 注册指令
		BackupCommands.register()


	}
}
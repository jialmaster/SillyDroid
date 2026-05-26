package com.jm.sillydroid.feature.settings.model

object SettingsNavigationPolicy {
    /**
     * 设置页正在执行宿主写入、清空、扩展安装、保存启动等任务时不能退出，
     * 避免用户返回后丢失任务进度或遗漏返回主界面需要消费的启动结果。
     */
    fun canFinish(isBusy: Boolean): Boolean {
        return !isBusy
    }
}

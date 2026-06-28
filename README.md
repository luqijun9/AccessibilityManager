# 无障碍管理器

<p align="center">
  <img src="https://img.shields.io/github/downloads/luqijun9/AccessibilityManager/total">
</p>

本APP可以彻底取代系统设置里的无障碍设置页面。仅需要授权本APP写入安全设置即可使用。支持无障碍保活，不耗电不主动唤醒，且保活速度极快。

**forked from** [**WuDi-ZhanShen/AccessibilityManager**](https://github.com/WuDi-ZhanShen/AccessibilityManager)

在原项目基础上添加了崩溃检测功能，具体说明如下：

1\. 崩溃检测：检测无障碍服务是否假死（已开启但显示"无法运行")

2\. 解锁检测：需开启管理器无障碍服务或自启动权限，否则可能无效

3\. 重启强杀app：默认重启方式为直接重启服务，勾选后强制停止APP后再重启

4\. 定时检测：定时检测服务状态，建议间隔≥5分钟

5\. 延迟1秒保活：延迟1秒执行服务重启，某些情况下也许能提高成功率


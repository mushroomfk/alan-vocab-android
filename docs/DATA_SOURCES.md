# 数据来源与词库格式

2026-10-14：开源版只包含本次为项目新编写的 2 本示例词书、40 条常用词，中文释义与英文例句没有从原版词库复制，随项目使用 MIT 许可。它们用来体验完整学习流程，不代表考试大纲或完整词典；音标字段暂空，发音由设备 TTS 提供。

原版 67 套第三方词库及其词性、扩展学习内容数据未随本仓库发布，其授权范围尚未确认。

## 加入有权分发的词库

目前通过修改构建资产加入词书，应用没有通用词库文件导入界面。JSON 学习备份导入与词库导入是不同功能。

1. 在 `app/src/main/assets/dicts/` 创建 JSON 数组文件，每条至少提供 `name`、`trans`，建议填写词性和原创或获授权的例句。
2. 在 `app/src/main/assets/dictionary-manifest.json` 的 `dictionaries` 新增记录，使用唯一 `id` 和唯一 `orderIndex`，`url` 指向 `/dicts/文件名.json`，填写 `name`、`length`、`category`；更新 `dictionaryCount` 和 `version`。
3. 保留默认的 `demo-basics` 词书；默认选书写在 `PreferencesStore.kt`。如改变默认词书，同步调整默认设置和测试。
4. 记录来源链接、作者、许可名称、是否允许修改及再分发、修改内容和日期，并附上所需许可文件。构建后在独立模拟器验证选书、学习和备份。

示例词条：

```json
{
  "name": "learn",
  "trans": ["学习"],
  "partOfSpeech": "v.",
  "example": "I learn one word before breakfast.",
  "exampleTranslation": "我在早餐前学一个单词。",
  "chapter": 1,
  "unitId": "unit-1",
  "unitName": "入门练习",
  "orderIndex": 1
}
```

单词内部 ID 根据词书 `orderIndex` 和 JSON 数组下标生成。已经发布的词书不能随意调整顺序或复用 ID，否则会造成学习记录错配。当前导入逻辑通常跳过已存在的词书，增加 manifest 版本号也不会自动刷新所有既有词条；修改旧词书需要明确编写并验证保留进度和收藏的迁移，不能删除数据库重新导入。

示例版与原版应用的数据空间独立。由于词库不同，不支持使用原版备份进行完整进度迁移。

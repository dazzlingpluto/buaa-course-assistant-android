# GSMIS 正式课表 · v2.2.1

应用根据教务认证和实际课表响应选择数据源。校历和签到规则见 [正式课表说明](ACADEMIC_TIMETABLE.md)。

研究生请求依次为：

- GET `https://gsmis.buaa.edu.cn/gsapp/sys/wdkbapp/*default/index.do`，建立教务会话。
- POST `.../modules/xskcb/kfdxnxqcx.do`，取得学期列表。
- POST `.../bykb/loadXskbData.do`，表单 `ZC=""`、`XNXQDM=学期代码`、`XH=""`、`XQDM=""`。

`GraduateTimetableParser.kt` 按教学班关联 `rwList/jgList`，使用 `ZCBH` 周掩码、`XQ` 星期及 `jcfaList.skjcList` 节次时间，并交叉校验首次上课日期与周次。未排课任务不会令整表失败，任务和排课身份冲突则报错。周次范围来自接口返回的位图。2026 秋季缺少校历证据时使用 9 月 7 日作为起点。

历史 v2.0.2 的接口路径与字段参考过 [Yiki21/iclass_buaa_tui](https://github.com/Yiki21/iclass_buaa_tui)。

测试使用构造响应，真实学校账号和手机验证仍待进行。

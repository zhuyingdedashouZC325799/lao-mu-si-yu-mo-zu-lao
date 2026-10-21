# 老牧祀与魔族佬

一个 Minecraft 模组：把两个 MMD 模型做成一对真实的中立生物，
在八格内互相察觉到对方时会转身对视、对口型合唱同一首歌；被玩家攻击或遇到敌对
生物时会停唱、追上去把对方踹死，打完再回到原位接着唱。

---

## 一、下载与安装

### 支持的版本

| 游戏版本 | 加载器 | 需要的 Java | 说明 |
|---|---|---|---|
| **26.2** | Fabric 0.20.0+ | Java 25 | 最早完成、实机验证通过 |
| **26.1.2** | Fabric 0.19.2+ | Java 25 | 与 26.2 共用同一套源码 |
| **26.1.2** | NeoForge 26.1.2.107 | Java 25 | 独立注册层，见 `src/neoforge` |
| **1.20.1** | Forge 47.4.20 | Java 17+ | 独立源码树，见 `src/forge1201` |

成品 jar 都在 [Releases](../../releases) 页，对应的 tag 为
`v1.0.0-fabric-26.2`、`v1.0.0-fabric-26.1.2`、`v1.0.0-neoforge-26.1.2`、
`v1.0.0-forge-1.20.1`。

### 装法

1. 装好上表对应游戏版本与加载器。
2. Fabric 版把 [Fabric API](https://modrinth.com/mod/fabric-api) 放进 `mods`；
   NeoForge 与 Forge 版不需要额外前置。
3. 把对应版本的 jar 放进 `.minecraft/mods`。
4. 需要对应版本的 **Java**（26.x 为 Java 25，1.20.1 为 Java 17+）。

可选但推荐：**AttributeFix**。本模组通过 `HealthCeiling` 自己放宽了原版
`max_health` 的 1024 上限，所以不装也能正常显示 1325 点血；装了只是让其它
读取该属性的模组也统一口径。1.20.1 的 Forge 运行时是 SRG 映射，`HealthCeiling`
按 `maxValue` / `f_22308_` / `field_22308` 三个候选名依次反射，并带"值等价"
兜底扫描，所以重映射不会让上限静默回落成 1024。

兼容性方面声明了对 **JEI** 与 **Jade（玉）** 的软依赖：两个刷怪蛋会自动进入
JEI 的物品列表，Jade 探头能看到名字与血量。

---

## 二、上手

创造模式物品栏里有两只刷怪蛋：

| 刷怪蛋 | 实体 | 血量 |
|---|---|---|
| 老牧祀 | `mmdduet:si` | **1325**（662.5 心） |
| 梨诺 | `mmdduet:liino` | **325**（162.5 心） |

也可以用命令直接召唤：

```
/summon mmdduet:si
/summon mmdduet:liino
```

### 对唱

两只不同变体的生物走进 **8 格**内就互相察觉，随后：

- 双方自动把**身体和头**转向对方（只朝彼此，不追玩家）；
- 走到 **2 格**左右停下，不再挪动；
- 开始播放同一首曲子，嘴巴跟着口型开合；
- 玩家在附近任意位置都能听到，距离越近越清楚。

没有同伴时它们不会呆站着，而是慢速四处游荡。

### 战斗

这两只是**中立**生物，不会主动找玩家麻烦，但：

- 玩家打了其中任何一只 → **两只一起**转火，冲上来踹人；
- 附近出现敌对生物 → 主动清怪；
- 每次踹击造成 **3.25 点真实伤害**（约 1.625 心），无视护甲、保护附魔、
  抗性提升、伤害吸收和盾牌；
- 攻击间隔 **1 秒**，命中会把玩家往**正面方向**击退；
- 打架期间**停止唱歌**，把威胁清完、重新聚拢之后再接着唱。

和平难度下不会攻击玩家——这是原版对"生物能否把玩家当目标"的限制，
本模组有意不去绕过它。

---

## 三、实现要点

### 模型

两个模型都是从 PMX 烘焙出来的**真三维网格**，不是贴图朝向摄像机的那种伪 3D：

| | 顶点 | 三角面 | 材质 | 贴图 |
|---|---|---|---|---|
| 老牧祀 | 63 503 | 73 037 | 12 | 7 |
| 梨诺 | 81 998 | 96 394 | 22 | 9 |

网格与骨骼在构建期解算好，运行期只做线性混合蒙皮，贴图按 MMD 的
`*_d.png` 原图直接采样，所以进游戏不会出现"糊成一团"或"彩色乱码"的常见
失败模式（后者通常是把未解码的材质索引当成颜色用了）。

### 动画

- **走路**：肢体真正摆动，脚掌按行程贴地，不是整体平移；
- **口型**：由音频播放位置驱动，唱到哪个音嘴就张多大；
- **踹击**：16 tick 完整动作，第 9 tick 落脚命中，动作与判定同一帧；
- **转身**：对唱时身体与头分别用不同速率收敛，头先到位。

### 血量上限

原版 `max_health` 的合法区间上限是 1024，超出的值会被**静默夹断**。
`HealthCeiling` 在启动时用反射把这个区间改宽，因此属性里存的就是真实值——
血条、伤害计算和 Jade 读数三方一致，存档读档也不丢。

1.20.1 上还有一层坑：Forge 用 SRG 名字重映射字节码，`RangedAttribute` 的字段
在运行期不叫 `maxValue` 而是 `f_22308_`。只认 `maxValue` 会抛
`NoSuchFieldException` 并被 `catch` 吞掉，结果血量静静停在 1024。所以
`HealthCeiling` 同时尝试三个名字，都失败时再遍历 `double` 字段找出"当前报告值"
对应的那一个，确保换任何映射环境都能改成功。

---

## 四、从源码构建

需要 JDK 25 和一个解开依赖的 classpath 目录。默认脚本按作者环境的路径写死，
换机器时改 `tools/build.py` 里的 `JDK`、以及 `cp262short.txt` 里的 classpath。

```bat
:: 1. 编译（源码在 src/fabric/java）
python tools/build.py src\fabric\java cp262short.txt build262\classes --release 25

:: 2. 生成资源树（写入 fabric.mod.json 的版本区间）
python tools/makeres.py 262

:: 3. 打成 jar（输出到 out/）
python tools/packzh.py

:: 4. 自检：条目齐不齐、class 在不在
python tools/jarverify.py out\lao-mu-si-yu-mo-zu-lao-1.0.0-fabric-26.2.jar
```

NeoForge 版：

```bat
python tools/build.py src\neoforge\java cp261neoshort.txt build261neo\classes --release 25
python tools/makeres.py 261neo
python tools/packver.py build261neo
python tools/jarverify.py out\lao-mu-si-yu-mo-zu-lao-1.0.0-neoforge-26.1.2.jar
```

Forge 1.20.1 版（多一道 remap：先编译成官方映射，再重映射到 SRG）：

```bat
python tools/build.py src\forge1201\java cp1201.txt build1201\classes --release 17
python tools/remap.py D:\mmdmap1201\to_srg.tsv build1201\classes build1201f\classes_srg dir <client-srg.jar>
python tools/makeres.py 1201forge
python tools/packver.py build1201f
python tools/jarverify.py out\lao-mu-si-yu-mo-zu-lao-1.0.0-forge-1.20.1.jar
```

`tools/makeres.py` 里所有目标版本的依赖区间都写死在 `TARGETS` /
`NEOFORGE_TARGETS` 表中，这是为了绕开 Windows `cmd` 吞引号导致
`">=26.2 <26.3"` 被拼成一个词的老问题。NeoForge 分支还会额外写出
`META-INF/neoforge.mods.toml` 与 `pack.mcmeta`，并确保 `fabric.mod.json`
不被打进 jar——同一个 jar 里出现两套元数据会导致加载器直接拒载。

---

## 五、目录结构

```
src/fabric/java/com/hanafubuki/duet/     Fabric 注册层 + 全部游戏逻辑
src/neoforge/java/com/hanafubuki/duet/   NeoForge 注册层（DeferredRegister）
src/forge1201/java/com/hanafubuki/duet/  Forge 1.20.1 注册层（SRG 重映射）
  DuetMod.java            注册实体、物品、音效，装配 AI
  SingerEntity.java       实体本体：对唱、战斗、仇恨、走位
  WanderGoal.java         没有同伴时的慢速游荡
  RageTargetGoal.java     选目标：记仇的玩家优先，其次是敌对生物
  KickAttackGoal.java     踹击的时机与判定
  DuetDamage.java         踹击的真实伤害类型
  HealthCeiling.java      放宽 max_health 上限
  mesh/                   MMD 网格、骨骼、蒙皮、动画
  client/                 渲染器、音轨、口型驱动
src/fabric/resources/
  assets/mmdduet/         烘焙网格、贴图、音效、语言文件、物品模型
  data/                   伤害类型与 "无视护甲" 等标签
tools/                    构建与自检脚本
dist/                     Release 说明与发布参数
```

两个加载器共用同一个 `com.hanafubuki.duet` 包名和同一套游戏逻辑：26.x 的
Fabric 与 NeoForge 都使用 Mojang 官方映射，所以实体、AI、网格、渲染器这些
文件可以逐字节相同，只有注册入口和事件挂钩不同。`src/neoforge` 里因此只有
`DuetMod` 与 `DuetClient` 两个文件需要真正改写，其余文件是从 `src/fabric`
同步过来的。

---

## 六、已知限制

- 26.2 / 26.1.2 Fabric、26.1.2 NeoForge、1.20.1 Forge 均已实机验证。
- 1.20.1 用的是旧版 `EntityRenderer` 体系与 SRG 映射，源码独立于 26.x，
  修 bug 时需要两边同步。
- 极端光照类光影包会把实体着色压黑——**原版生物同样如此**，属光影包行为，
  与本模组的渲染实现无关。
- 模型是烘焙产物而非运行期加载 PMX，换模型要重新走烘焙流程。
- 名字牌高度是按当前模型头顶位置硬编码的，换模型后需要跟着调。

## 七、致谢

模型与音频由项目作者提供；模组的玩法参考了原作者分享的演示视频。
本项目与模型、音频的原始版权方无隶属关系。

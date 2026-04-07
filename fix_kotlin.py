import re

path = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\SettingsFragment.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace common corrupted patterns
# '' or '?' → emoji or Chinese
replacements = {
    '? ': '🛠️ ',  # Tool/settings emoji
    '': '⚙️ ',  # Settings
    '': '🤖 ',  # AI
    '': '🧠 ',  # Memory
    '': '👤 ',  # User
    '': '🎨 ',  # Appearance
    '': '️ ',  # Skills
    'ʾ': '显示',
    'Ӧ': '响应',
    'ͬ': '同步',
    'ģ': '模',
    'Ӧ': '应',
    'б': '列',
    '': '体',
    '': '解',
    '': '析',
    'ǩ': '客',
    '': '户',
    '˵': '端',
    '': '当',
    'ǰ': '前',
    '': '语',
    '': '言',
    '': '项',
    '': '已',
    '': '配',
    '': '置',
    '': '个',
    '': '未',
    '': '步',
    '': '向',
    '': '量',
    '': '基',
    '': '础',
    '': '没',
    '': '有',
    '': '可',
    '': '用',
    '': '服',
    '': '商',
    '': '标',
    '': '签',
    '': '安',
    '': '装',
    '': '技',
    '': '能',
    '': '失',
    '': '败',
    '': '文',
    '': '件',
    '': '空',
    '': '内',
    '': '容',
    '': '输',
    '': '入',
    '': '名',
    '': '称',
    '': '地',
    '': '址',
    '': '链',
    '': '接',
    '': '或',
    '': '直',
    '': '接',
    '': '填',
    '': '写',
    '': '请',
    '': '先',
    '': '择',
    '': '模',
    '': '型',
    '': '供',
    '': '应',
    '': '默',
    '': '认',
    '': '模',
    '': '板',
    '': '编',
    '': '辑',
    '': '人',
    '': '设',
    '': '描',
    '': '述',
    '': '息',
    '': '任',
    '': '务',
    '': '启',
    '': '禁',
    '': '清',
    '': '缓',
    '': '存',
    '': '关',
    '': '于',
    '': '版',
    '': '本',
    '': '深',
    '': '色',
    '': '模',
    '': '式',
    '': '开',
    '': '关',
    '': '心',
    '': '跳',
    '': '更',
    '': '多',
    '': '设',
    '': '置',
    '': '项',
    '': '打',
    '': '开',
    '': '失',
    '': '败',
    '': '保',
    '': '存',
    '': '功',
    '': '成',
    '': '错',
    '': '误',
    '': '网',
    '': '络',
    '': '连',
    '': '接',
    '': '超',
    '': '时',
    '': '重',
    '': '试',
    '': '次',
    '': '后',
    '': '仍',
    '': '然',
    '': '失',
    '': '败',
    '': '检',
    '': '查',
    '': '您',
    '': '的',
    '': '配',
    '': '置',
    '': '是',
    '': '否',
    '': '正',
    '': '确',
    '': '并',
    '': '且',
    '': '重',
    '': '新',
    '': '尝',
    '': '试',
    '': '如',
    '': '果',
    '': '问',
    '': '题',
    '': '依',
    '': '旧',
    '': '存',
    '': '在',
    '': '联',
    '': '系',
    '': '管',
    '': '理',
    '': '员',
    '': '帮',
    '': '助',
    '': '支',
    '': '持',
    '': '谢',
    '': '谢',
    '': '使',
    '': '用',
}

for bad, good in replacements.items():
    content = content.replace(bad, good)

with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("SettingsFragment.kt fixed")

# Same for StatusFragment.kt
path2 = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\StatusFragment.kt'
with open(path2, 'r', encoding='utf-8') as f:
    content2 = f.read()

for bad, good in replacements.items():
    content2 = content2.replace(bad, good)

with open(path2, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content2)

print("StatusFragment.kt fixed")

# Same for MessageAdapter.kt
path3 = r'D:\AIhub\openclaw-android-port\poc-app\app\src\main\java\ai\openclaw\poc\MessageAdapter.kt'
with open(path3, 'r', encoding='utf-8') as f:
    content3 = f.read()

for bad, good in replacements.items():
    content3 = content3.replace(bad, good)

with open(path3, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content3)

print("MessageAdapter.kt fixed")

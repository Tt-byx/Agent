/**
 * 中文文字→口型映射器
 * 根据汉字韵母映射嘴形(ParamMouthForm)和张嘴幅度(ParamMouthOpenY)
 */

// 韵母分类→口型参数
// MouthForm: -1=嘟嘴(i/u/ü), 0=自然, 1=咧嘴(a/e)
// OpenY: 0=闭合, 1=大张
interface VisemeParams {
  form: number  // ParamMouthForm value
  open: number  // ParamMouthOpenY multiplier
}

// 韵母→口型映射表
const VOWEL_MAP: Record<string, VisemeParams> = {
  // 大张嘴 (a, o 类)
  'a': { form: 0.8, open: 1.0 },
  'o': { form: 0.3, open: 0.8 },
  'e': { form: 0.6, open: 0.7 },

  // 扁嘴 (i, ü 类)
  'i': { form: -0.8, open: 0.3 },
  'u': { form: -0.6, open: 0.4 },
  'v': { form: -0.7, open: 0.35 },  // ü

  // 复合韵母
  'ai': { form: 0.7, open: 0.9 },
  'ei': { form: 0.2, open: 0.6 },
  'ao': { form: 0.5, open: 0.8 },
  'ou': { form: 0.0, open: 0.6 },
  'an': { form: 0.6, open: 0.7 },
  'en': { form: 0.0, open: 0.5 },
  'ang': { form: 0.7, open: 0.8 },
  'eng': { form: 0.1, open: 0.5 },
  'ong': { form: -0.2, open: 0.6 },
  'ia': { form: -0.3, open: 0.7 },
  'ie': { form: -0.4, open: 0.5 },
  'iao': { form: -0.2, open: 0.7 },
  'iu': { form: -0.5, open: 0.5 },
  'ian': { form: -0.5, open: 0.6 },
  'in': { form: -0.6, open: 0.4 },
  'iang': { form: -0.3, open: 0.7 },
  'ing': { form: -0.5, open: 0.4 },
  'iong': { form: -0.4, open: 0.5 },
  'ua': { form: 0.3, open: 0.8 },
  'uo': { form: 0.0, open: 0.6 },
  'uai': { form: 0.3, open: 0.8 },
  'ui': { form: -0.3, open: 0.5 },
  'uan': { form: 0.1, open: 0.6 },
  'un': { form: -0.3, open: 0.4 },
  'uang': { form: 0.2, open: 0.7 },
  'er': { form: 0.3, open: 0.5 },
}

// 声母→口型（辅音阶段嘴型变化小）
const CONSONANT_MAP: Record<string, VisemeParams> = {
  'b': { form: -0.2, open: 0.1 },
  'p': { form: -0.2, open: 0.2 },
  'm': { form: -0.3, open: 0.15 },
  'f': { form: -0.4, open: 0.15 },
  'd': { form: 0.0, open: 0.2 },
  't': { form: 0.0, open: 0.25 },
  'n': { form: 0.0, open: 0.15 },
  'l': { form: 0.1, open: 0.2 },
  'g': { form: 0.2, open: 0.15 },
  'k': { form: 0.2, open: 0.25 },
  'h': { form: 0.3, open: 0.2 },
  'j': { form: -0.5, open: 0.1 },
  'q': { form: -0.5, open: 0.15 },
  'x': { form: -0.5, open: 0.1 },
  'zh': { form: 0.0, open: 0.2 },
  'ch': { form: 0.0, open: 0.3 },
  'sh': { form: 0.0, open: 0.15 },
  'r': { form: 0.0, open: 0.15 },
  'z': { form: 0.0, open: 0.2 },
  'c': { form: 0.0, open: 0.25 },
  's': { form: 0.0, open: 0.15 },
  'y': { form: -0.3, open: 0.1 },
  'w': { form: -0.3, open: 0.15 },
}

// 常见汉字→韵母快速映射（高频字直接映射，避免拼音库依赖）
// 仅覆盖最常用字，其余用通用口型
const CHAR_TO_VOWEL: Record<string, string> = {
  '的': 'e', '一': 'i', '是': 'i', '不': 'u', '了': 'e',
  '在': 'ai', '人': 'en', '有': 'ou', '我': 'o', '他': 'a',
  '这': 'e', '中': 'ong', '大': 'a', '来': 'ai', '上': 'ang',
  '国': 'uo', '个': 'e', '到': 'ao', '说': 'uo', '们': 'en',
  '为': 'ei', '子': 'i', '和': 'e', '你': 'i', '地': 'i',
  '出': 'u', '会': 'ui', '时': 'i', '要': 'ao', '可': 'e',
  '也': 'e', '她': 'a', '好': 'ao', '年': 'ian', '过': 'uo',
  '没': 'ei', '后': 'ou', '自': 'i', '以': 'i', '生': 'eng',
  '那': 'a', '里': 'i', '去': 'ü', '就': 'iu', '得': 'e',
  '都': 'ou', '对': 'ui', '看': 'an', '下': 'ia', '起': 'i',
  '么': 'e', '样': 'ang', '想': 'iang', '能': 'eng', '很': 'en',
  '什': 'en', '么': 'e', '美': 'ei', '太': 'ai', '还': 'ai',
  '让': 'ang', '把': 'a', '着': 'e', '做': 'uo', '最': 'ui',
  '老': 'ao', '小': 'iao', '少': 'ao', '多': 'uo', '长': 'ang',
  '开': 'ai', '只': 'i', '回': 'ui', '两': 'iang', '新': 'in',
  '些': 'ie', '进': 'in', '从': 'ong', '前': 'ian', '面': 'ian',
  '点': 'ian', '走': 'ou', '问': 'en', '同': 'ong', '法': 'a',
  '加': 'ia', '被': 'ei', '给': 'ei', '而': 'er', '又': 'ou',
  '与': 'ü', '之': 'i', '于': 'ü', '及': 'i', '其': 'i',
  '所': 'uo', '然': 'an', '已': 'i', '经': 'ing', '因': 'in',
  '此': 'i', '如': 'u', '但': 'an', '发': 'a', '成': 'eng',
  '事': 'i', '部': 'u', '无': 'u', '之': 'i', '用': 'ong',
  '方': 'ang', '行': 'ing', '作': 'uo', '日': 'i', '月': 'ue',
  '水': 'ui', '山': 'an', '风': 'eng', '天': 'ian', '地': 'i',
  '花': 'a', '鸟': 'iao', '景': 'ing', '区': 'ü', '游': 'ou',
  '客': 'e', '欢': 'uan', '迎': 'ing', '您': 'in', '请': 'ing',
  '谢': 'ie', '见': 'ian', '高': 'ao', '兴': 'ing', '帮': 'ang',
  '忙': 'ang', '介': 'ie', '绍': 'ao', '知': 'i', '道': 'ao',
  '乐': 'e', '快': 'uai', '慢': 'an', '远': 'uan', '近': 'in',
  '左': 'uo', '右': 'ou', '北': 'ei', '南': 'an', '东': 'ong',
  '西': 'i', '门': 'en', '车': 'e', '站': 'an', '吃': 'i',
  '喝': 'e', '玩': 'an', '买': 'ai', '卖': 'ai', '钱': 'ian',
  '票': 'iao', '等': 'eng', '位': 'ei', '找': 'ao', '需': 'ü',
  '帮': 'ang', '心': 'in', '意': 'i', '怎': 'en', '么': 'e',
  '哪': 'a', '几': 'i', '多少': 'uo', '钱': 'ian', '贵': 'ui',
  '便': 'ian', '宜': 'i', '热': 'e', '冷': 'eng', '累': 'ei',
  '饿': 'e', '渴': 'e', '厕': 'e', '所': 'uo', '出': 'u',
  '入': 'u', '口': 'ou', '禁': 'in', '止': 'i', '拍': 'ai',
  '照': 'ao', '安': 'an', '全': 'uan', '注': 'u', '意': 'i',
}

// 标点符号→停顿口型
const PUNCTUATION_FORM: Record<string, VisemeParams> = {
  '，': { form: 0, open: 0 },
  '。': { form: 0, open: 0 },
  '！': { form: 0.2, open: 0.3 },
  '？': { form: 0.1, open: 0.2 },
  '、': { form: 0, open: 0 },
  '；': { form: 0, open: 0 },
  '：': { form: 0, open: 0.1 },
  '…': { form: 0, open: 0.05 },
  '~': { form: 0.3, open: 0.1 },
  '!': { form: 0.2, open: 0.3 },
  '?': { form: 0.1, open: 0.2 },
  '.': { form: 0, open: 0 },
  ',': { form: 0, open: 0 },
}

/**
 * 根据单个字符获取口型参数
 */
export function getVisemeForChar(char: string): VisemeParams {
  // 标点
  if (PUNCTUATION_FORM[char]) return PUNCTUATION_FORM[char]

  // 空格/换行
  if (char === ' ' || char === '\n') return { form: 0, open: 0 }

  // 英文字母
  if (/[a-zA-Z]/.test(char)) {
    const lower = char.toLowerCase()
    if ('aeiou'.includes(lower)) {
      return VOWEL_MAP[lower] || { form: 0.2, open: 0.5 }
    }
    return { form: 0, open: 0.2 }
  }

  // 汉字
  const vowel = CHAR_TO_VOWEL[char]
  if (vowel && VOWEL_MAP[vowel]) {
    return VOWEL_MAP[vowel]
  }

  // 未知汉字：使用通用口型（中等张嘴，自然嘴形）
  return { form: 0.1, open: 0.5 }
}

/**
 * 将文字转换为口型序列
 * @param text 输入文字
 * @param charDuration 每个字符持续时间(毫秒)，默认 180ms
 */
export function textToVisemeSequence(text: string, charDuration = 180): Array<{ time: number; form: number; open: number }> {
  const sequence: Array<{ time: number; form: number; open: number }> = []
  let currentTime = 0

  for (const char of text) {
    const viseme = getVisemeForChar(char)
    sequence.push({
      time: currentTime,
      form: viseme.form,
      open: viseme.open,
    })

    // 标点停顿更久
    const isPunctuation = PUNCTUATION_FORM[char] !== undefined
    const isPause = isPunctuation || char === ' '
    currentTime += isPause ? charDuration * 1.5 : charDuration
  }

  return sequence
}
import { describe, expect, it } from 'vitest'
import { applyMention, filterMembers, findActiveMention, splitMentions } from './mentions'

// Разбор @упоминаний (4.5). Клиентская половина обязана совпадать с серверной
// (MentionParser.java) в одном: что считать упоминанием, а что — обычным текстом.
// Разъезд здесь виден сразу и обидно: подсвеченный никнейм, по которому никого не позвали,
// или наоборот — уведомление там, где человек ничего такого не имел в виду.

const member = (username, lastName, firstName) => ({ userId: username, username, lastName, firstName })

describe('splitMentions', () => {
  it('вытаскивает упоминание и оставляет текст вокруг', () => {
    expect(splitMentions('Привет, @ivanov, глянь')).toEqual([
      { type: 'text', value: 'Привет, ' },
      { type: 'mention', username: 'ivanov' },
      { type: 'text', value: ', глянь' },
    ])
  })

  it('упоминание в самом начале строки', () => {
    expect(splitMentions('@ivanov глянь')).toEqual([
      { type: 'mention', username: 'ivanov' },
      { type: 'text', value: ' глянь' },
    ])
  })

  // Точка не входит в набор символов никнейма именно ради этого случая: конец предложения
  // не должен съедаться упоминанием и не должен мешать его разобрать.
  it('точка в конце предложения не мешает', () => {
    expect(splitMentions('спроси у @ivanov.')).toEqual([
      { type: 'text', value: 'спроси у ' },
      { type: 'mention', username: 'ivanov' },
      { type: 'text', value: '.' },
    ])
  })

  // То же правило, что на сервере: отличает упоминание от почтового адреса в тексте ровно
  // символ перед «@». Иначе строчка из письма, вставленная в комментарий, звала бы человека.
  it('почтовый адрес в тексте упоминанием не считается', () => {
    expect(splitMentions('пиши на ivan@example.com')).toEqual([
      { type: 'text', value: 'пиши на ivan@example.com' },
    ])
  })

  it('два упоминания подряд разбираются оба, пробел между ними остаётся текстом', () => {
    expect(splitMentions('@ivanov @petrov')).toEqual([
      { type: 'mention', username: 'ivanov' },
      { type: 'text', value: ' ' },
      { type: 'mention', username: 'petrov' },
    ])
  })

  it('регистр не мешает: бэкенд хранит и сравнивает в нижнем', () => {
    expect(splitMentions('@Ivanov')).toEqual([{ type: 'mention', username: 'ivanov' }])
  })

  // Без верхней границы «@» и тридцать пять символов подряд дали бы «упоминание» из первых
  // тридцати — то есть чужой никнейм, собранный из куска чужого слова.
  it('слишком длинное и слишком короткое упоминанием не считается', () => {
    expect(splitMentions('@' + 'a'.repeat(35))).toEqual([{ type: 'text', value: '@' + 'a'.repeat(35) }])
    expect(splitMentions('@ab')).toEqual([{ type: 'text', value: '@ab' }])
  })

  it('пустое тело — пустой список, а не падение', () => {
    expect(splitMentions('')).toEqual([])
    expect(splitMentions(null)).toEqual([])
  })
})

describe('findActiveMention', () => {
  it('каретка внутри набираемого упоминания', () => {
    expect(findActiveMention('глянь @iva', 10)).toEqual({ query: 'iva', start: 6 })
  })

  // Требований к набранному меньше, чем к готовому никнейму: подсказки нужны как раз тому,
  // кто ещё не дописал.
  it('только что набранный @ показывает всех: запрос пустой', () => {
    expect(findActiveMention('глянь @', 7)).toEqual({ query: '', start: 6 })
  })

  it('пробел после @ закрывает подсказки', () => {
    expect(findActiveMention('глянь @ кто-нибудь', 18)).toBeNull()
  })

  // Каретку двигают не только буквы: ушёл курсором назад — подсказки уже не про то место.
  it('каретка перед @ — упоминания нет', () => {
    expect(findActiveMention('глянь @iva', 3)).toBeNull()
  })

  it('@ внутри слова — это почтовый адрес или часть слова, а не начало упоминания', () => {
    expect(findActiveMention('ivan@exa', 8)).toBeNull()
  })
})

describe('applyMention', () => {
  it('подставляет никнейм вместо набранного и ставит каретку за ним', () => {
    const text = 'глянь @iva'
    const result = applyMention(text, findActiveMention(text, 10), 'ivanov')
    expect(result.text).toBe('глянь @ivanov ')
    expect(result.caret).toBe(result.text.length)
  })

  // Перед знаком препинания пробел не нужен: упоминание и так на нём кончается, а лишний
  // пробел пришлось бы стирать руками в самом частом случае — «@ivanov, посмотри».
  it('перед запятой пробел не дописывается, хвост строки сохраняется', () => {
    const text = 'глянь @iva, спасибо'
    const result = applyMention(text, findActiveMention(text, 10), 'ivanov')
    expect(result.text).toBe('глянь @ivanov, спасибо')
    expect(result.caret).toBe('глянь @ivanov'.length)
  })

  it('перед следующим словом пробел нужен — иначе оно приклеится к никнейму', () => {
    const text = 'глянь @iva пожалуйста'
    const result = applyMention(text, findActiveMention(text, 10), 'ivanov')
    expect(result.text).toBe('глянь @ivanov пожалуйста')
  })
})

describe('filterMembers', () => {
  const members = [
    member('ivanov', 'Иванов', 'Иван'),
    member('petrov-p', 'Петров', 'Пётр'),
  ]

  it('пустой запрос показывает всех', () => {
    expect(filterMembers(members, '')).toEqual(members)
  })

  it('ищет по никнейму', () => {
    expect(filterMembers(members, 'petrov')).toEqual([members[1]])
  })

  // Никнейм человек помнит не всегда, а фамилию — всегда; подсказка обязана находиться и так.
  it('ищет по фамилии', () => {
    expect(filterMembers(members, 'иванов')).toEqual([members[0]])
  })

  it('ничего не совпало — пустой список, а не все', () => {
    expect(filterMembers(members, 'сидоров')).toEqual([])
  })
})

import { describe, expect, it } from 'vitest'
import { applyMention, filterMembers, findActiveMention, splitMentions } from './mentions'

// Разбор @упоминаний (4.5). Клиентская половина обязана совпадать с серверной
// (MentionParser.java) в одном: что считать упоминанием, а что — обычным текстом.
// Разъезд здесь виден сразу и обидно: подсвеченное имя, по которому никого не позвали,
// или наоборот — уведомление там, где человек просто вставил в комментарий чей-то адрес.

const member = (email, lastName, firstName) => ({ userId: email, email, lastName, firstName })

describe('splitMentions', () => {
  it('вытаскивает упоминание и оставляет текст вокруг', () => {
    expect(splitMentions('Привет, @ivan@example.com, глянь')).toEqual([
      { type: 'text', value: 'Привет, ' },
      { type: 'mention', email: 'ivan@example.com' },
      { type: 'text', value: ', глянь' },
    ])
  })

  it('упоминание в самом начале строки', () => {
    expect(splitMentions('@ivan@example.com глянь')).toEqual([
      { type: 'mention', email: 'ivan@example.com' },
      { type: 'text', value: ' глянь' },
    ])
  })

  // То же правило, что на сервере: отличает упоминание от адреса ровно символ перед «@».
  // Иначе строчка из письма, вставленная в комментарий, звала бы человека в тред.
  it('адрес без ведущего @ упоминанием не считается', () => {
    expect(splitMentions('пиши на ivan@example.com')).toEqual([
      { type: 'text', value: 'пиши на ivan@example.com' },
    ])
  })

  it('два упоминания подряд разбираются оба, пробел между ними остаётся текстом', () => {
    expect(splitMentions('@a@x.com @b@y.com')).toEqual([
      { type: 'mention', email: 'a@x.com' },
      { type: 'text', value: ' ' },
      { type: 'mention', email: 'b@y.com' },
    ])
  })

  it('регистр адреса не мешает: бэкенд сравнивает в нижнем', () => {
    expect(splitMentions('@Ivan@Example.COM')).toEqual([{ type: 'mention', email: 'ivan@example.com' }])
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

  it('@ внутри слова — это адрес или часть слова, а не начало упоминания', () => {
    expect(findActiveMention('ivan@exa', 8)).toBeNull()
  })
})

describe('applyMention', () => {
  it('подставляет адрес вместо набранного и ставит каретку за ним', () => {
    const text = 'глянь @iva'
    const result = applyMention(text, findActiveMention(text, 10), 'ivan@example.com')
    expect(result.text).toBe('глянь @ivan@example.com ')
    expect(result.caret).toBe(result.text.length)
  })

  // Перед знаком препинания пробел не нужен: упоминание и так на нём кончается, а лишний
  // пробел пришлось бы стирать руками в самом частом случае — «@Иванов, посмотри».
  it('перед запятой пробел не дописывается, хвост строки сохраняется', () => {
    const text = 'глянь @iva, спасибо'
    const result = applyMention(text, findActiveMention(text, 10), 'ivan@example.com')
    expect(result.text).toBe('глянь @ivan@example.com, спасибо')
    expect(result.caret).toBe('глянь @ivan@example.com'.length)
  })

  it('перед следующим словом пробел нужен — иначе оно приклеится к адресу', () => {
    const text = 'глянь @iva пожалуйста'
    const result = applyMention(text, findActiveMention(text, 10), 'ivan@example.com')
    expect(result.text).toBe('глянь @ivan@example.com пожалуйста')
  })
})

describe('filterMembers', () => {
  const members = [
    member('ivanov@example.com', 'Иванов', 'Иван'),
    member('petrov@example.com', 'Петров', 'Пётр'),
  ]

  it('пустой запрос показывает всех', () => {
    expect(filterMembers(members, '')).toEqual(members)
  })

  it('ищет по фамилии', () => {
    expect(filterMembers(members, 'петр')).toEqual([members[1]])
  })

  it('ищет по адресу — его человек помнит реже, но подсказку по нему ждёт', () => {
    expect(filterMembers(members, 'ivanov@')).toEqual([members[0]])
  })

  it('ничего не совпало — пустой список, а не все', () => {
    expect(filterMembers(members, 'сидоров')).toEqual([])
  })
})

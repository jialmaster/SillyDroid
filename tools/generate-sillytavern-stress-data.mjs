import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const outputRoot = process.argv[2];
if (!outputRoot) {
  throw new Error('Usage: node tools/generate-sillytavern-stress-data.mjs <output-root> [--chats-only]');
}

const chatsOnly = process.argv.includes('--chats-only');
const prefix = 'STRESS_20260531';
const userRoot = path.join(outputRoot, 'data', 'default-user');
const dirs = {
  characters: path.join(userRoot, 'characters'),
  chats: path.join(userRoot, 'chats'),
  worlds: path.join(userRoot, 'worlds'),
  openai: path.join(userRoot, 'OpenAI Settings'),
  context: path.join(userRoot, 'context'),
  instruct: path.join(userRoot, 'instruct'),
  sysprompt: path.join(userRoot, 'sysprompt'),
};

const requiredDirs = chatsOnly ? [dirs.chats] : Object.values(dirs);
for (const dir of requiredDirs) {
  fs.mkdirSync(dir, { recursive: true });
}

const counts = {
  characters: 50,
  minChats: 125,
  maxChats: 260,
  messagesPerChat: 1000,
  messageCharacters: 1000,
  assistantSwipeCharacters: 1000,
  openAiPresets: 10,
  promptRolesPerPreset: 100,
  worlds: 50,
  worldEntriesPerWorld: 100,
};

const targetBytes = 1_073_741_824;
const initialWorldEntryRepeat = 24;
const initialPresetRoleRepeat = 18;

function repeatedParagraph(seed, repeat) {
  const base = [
    `stress-seed=${seed}`,
    '这是一段用于 SillyTavern Android 压力测试的假数据，包含角色设定、会话正文、世界书条目和预设提示词。',
    '内容有意保持可读并避免真实隐私；每一段都带有稳定编号，方便后续定位加载、检索、列表渲染和序列化性能问题。',
    'The archive is synthetic, deterministic, and safe to delete by prefix when the memory test is finished.',
  ].join(' ');
  return Array.from({ length: repeat }, (_, i) => `${base} paragraph=${i}`).join('\n');
}

function fixedLengthText(seed, characters) {
  const head = `【${prefix} ${seed}】`;
  const filler = '这段压力测试正文用于模拟移动端酒馆长会话加载、滚动、检索、序列化和 WebView 渲染时的真实文本体积。';
  if (head.length >= characters) {
    return head.slice(0, characters);
  }
  return (head + filler.repeat(Math.ceil((characters - head.length) / filler.length))).slice(0, characters);
}

function jsonWrite(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(value, null, 2));
}

function crc32(buffer) {
  let crc = ~0;
  for (let i = 0; i < buffer.length; i += 1) {
    crc ^= buffer[i];
    for (let j = 0; j < 8; j += 1) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return ~crc >>> 0;
}

function pngChunk(type, data) {
  const typeBuffer = Buffer.from(type, 'ascii');
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
  return Buffer.concat([length, typeBuffer, data, crc]);
}

function createPngCard(filePath, card) {
  const width = 2;
  const height = 2;
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    const row = y * (width * 4 + 1);
    raw[row] = 0;
    for (let x = 0; x < width; x += 1) {
      const off = row + 1 + x * 4;
      raw[off] = 80 + x * 40;
      raw[off + 1] = 120 + y * 40;
      raw[off + 2] = 180;
      raw[off + 3] = 255;
    }
  }

  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const encoded = Buffer.from(JSON.stringify(card), 'utf8').toString('base64');
  const textData = Buffer.concat([Buffer.from('chara\0', 'latin1'), Buffer.from(encoded, 'latin1')]);
  const image = Buffer.concat([
    signature,
    pngChunk('IHDR', ihdr),
    pngChunk('tEXt', textData),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 0 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);

  fs.writeFileSync(filePath, image);
}

function characterName(index) {
  return `${prefix}_角色_${String(index).padStart(2, '0')}`;
}

function createCharacter(index) {
  const name = characterName(index);
  const description = repeatedParagraph(`character-description-${index}`, 18);
  const personality = repeatedParagraph(`character-personality-${index}`, 10);
  const scenario = repeatedParagraph(`character-scenario-${index}`, 12);
  const firstMes = repeatedParagraph(`character-first-message-${index}`, 8);
  const card = {
    spec: 'chara_card_v2',
    spec_version: '2.0',
    data: {
      name,
      description,
      personality,
      scenario,
      first_mes: firstMes,
      mes_example: repeatedParagraph(`character-example-${index}`, 8),
      creator_notes: repeatedParagraph(`character-notes-${index}`, 4),
      system_prompt: '',
      post_history_instructions: '',
      alternate_greetings: [repeatedParagraph(`alt-greeting-${index}-0`, 3), repeatedParagraph(`alt-greeting-${index}-1`, 3)],
      tags: ['stress-test', 'android', 'fake-data', `batch-${prefix}`],
      creator: 'SillyDroid stress generator',
      character_version: '1.0.0',
      extensions: {
        talkativeness: 0.5,
        fav: false,
        world: `${prefix}_世界书_${String(((index - 1) % counts.worlds) + 1).padStart(2, '0')}`,
      },
    },
  };
  createPngCard(path.join(dirs.characters, `${name}.png`), card);
  fs.mkdirSync(path.join(dirs.chats, name), { recursive: true });
}

function createChat(index) {
  const characterIndex = ((index - 1) % counts.characters) + 1;
  const name = characterName(characterIndex);
  const chatDir = path.join(dirs.chats, name);
  fs.mkdirSync(chatDir, { recursive: true });
  const fileName = `${name} - ${prefix} 会话 ${String(index).padStart(3, '0')}.jsonl`;
  const filePath = path.join(chatDir, fileName);
  const lines = [];
  lines.push(JSON.stringify({
    chat_metadata: {
      integrity: `${prefix}-chat-${index}`,
      note_prompt: '',
      note_interval: 1,
      note_position: 1,
      note_depth: 4,
      note_role: 0,
      variables: {},
      timedWorldInfo: { sticky: {}, cooldown: {} },
      tainted: true,
    },
    user_name: '压力测试用户',
    character_name: name,
  }));
  for (let messageIndex = 1; messageIndex <= counts.messagesPerChat; messageIndex += 1) {
    const isUser = messageIndex % 2 === 0;
    const speaker = isUser ? '压力测试用户' : name;
    lines.push(JSON.stringify({
      name: speaker,
      is_user: isUser,
      is_system: false,
      send_date: new Date(Date.UTC(2026, 4, 31, 3, index % 60, messageIndex % 60)).toISOString(),
      // 聊天是本压测数据的主压力源：每个会话 1000 楼，每楼正文固定 1000 个可见字符。
      mes: fixedLengthText(`chat-${index}-message-${messageIndex}`, counts.messageCharacters),
      extra: { isSmallSys: false, reasoning: '' },
      swipes: isUser ? undefined : [fixedLengthText(`chat-${index}-message-${messageIndex}-swipe`, counts.assistantSwipeCharacters)],
      swipe_id: isUser ? undefined : 0,
      variables: [{}],
      variables_initialized: [true],
      is_ejs_processed: [true],
    }));
  }
  fs.writeFileSync(filePath, lines.join('\n') + '\n');
}

function createWorld(index, fillerRepeat) {
  const name = `${prefix}_世界书_${String(index).padStart(2, '0')}`;
  const entries = {};
  for (let entry = 0; entry < counts.worldEntriesPerWorld; entry += 1) {
    entries[String(entry)] = {
      uid: entry,
      key: [`${prefix.toLowerCase()}_world_${index}_entry_${entry}`, `压力测试${index}_${entry}`],
      keysecondary: [],
      comment: `${name} 条目 ${entry}`,
      content: repeatedParagraph(`world-${index}-entry-${entry}`, fillerRepeat),
      constant: entry % 20 === 0,
      selective: entry % 20 !== 0,
      order: 100 + entry,
      position: entry % 2,
      disable: false,
      displayIndex: entry,
      addMemo: true,
      group: '',
      groupOverride: false,
      groupWeight: 100,
      sticky: 0,
      cooldown: 0,
      delay: 0,
      probability: 100,
      depth: 4,
      useProbability: false,
      role: null,
      vectorized: false,
      excludeRecursion: false,
      preventRecursion: false,
      delayUntilRecursion: false,
      scanDepth: null,
      caseSensitive: null,
      matchWholeWords: null,
      useGroupScoring: null,
      automationId: '',
      selectiveLogic: 0,
      ignoreBudget: false,
      matchPersonaDescription: false,
      matchCharacterDescription: false,
      matchCharacterPersonality: false,
      matchCharacterDepthPrompt: true,
      matchScenario: false,
      matchCreatorNotes: false,
      outletName: '',
      triggers: [],
    };
  }
  jsonWrite(path.join(dirs.worlds, `${name}.json`), { entries });
}

function createPreset(index, fillerRepeat) {
  const name = `${prefix}_预设_${String(index).padStart(2, '0')}`;
  const prompts = [];
  for (let roleIndex = 0; roleIndex < counts.promptRolesPerPreset; roleIndex += 1) {
    prompts.push({
      name: `${name}_role_${String(roleIndex).padStart(3, '0')}`,
      system_prompt: true,
      role: roleIndex % 3 === 0 ? 'system' : roleIndex % 3 === 1 ? 'user' : 'assistant',
      content: repeatedParagraph(`preset-${index}-role-${roleIndex}`, fillerRepeat),
      identifier: `${prefix.toLowerCase()}_preset_${index}_role_${roleIndex}`,
    });
  }
  const preset = {
    chat_completion_source: 'openai',
    openai_model: 'gpt-4-turbo',
    temperature: 1,
    top_p: 1,
    openai_max_context: 131072,
    openai_max_tokens: 2048,
    names_behavior: 0,
    wi_format: '{0}',
    scenario_format: '{{scenario}}',
    personality_format: '{{personality}}',
    prompts,
    prompt_order: [{ character_id: 100001 + index, order: prompts.map(prompt => ({ identifier: prompt.identifier, enabled: true })) }],
  };
  jsonWrite(path.join(dirs.openai, `${name}.json`), preset);
}

function dirSize(dir) {
  let total = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const filePath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      total += dirSize(filePath);
    } else if (entry.isFile()) {
      total += fs.statSync(filePath).size;
    }
  }
  return total;
}

let size = dirSize(outputRoot);
let chatCount = 0;

if (chatsOnly) {
  // 只补足已有压测会话，不触碰角色卡、世界书和预设，避免导入时重建整套数据。
  while (chatCount < counts.minChats) {
    chatCount += 1;
    createChat(chatCount);
  }
  size = dirSize(outputRoot);
} else {
  for (let i = 1; i <= counts.characters; i += 1) createCharacter(i);
  for (let i = 1; i <= counts.openAiPresets; i += 1) createPreset(i, initialPresetRoleRepeat);
  for (let i = 1; i <= counts.worlds; i += 1) createWorld(i, initialWorldEntryRepeat);

  size = dirSize(outputRoot);
  while ((chatCount < counts.minChats || size < targetBytes) && chatCount < counts.maxChats) {
    chatCount += 1;
    createChat(chatCount);
    size = dirSize(outputRoot);
  }
}

const manifest = {
  prefix,
  generatedAt: new Date().toISOString(),
  mode: chatsOnly ? 'chats-only' : 'full',
  targetBytes,
  actualBytes: size,
  counts: { ...counts, chats: chatCount },
  paths: {
    characters: 'data/default-user/characters',
    chats: 'data/default-user/chats',
    worlds: 'data/default-user/worlds',
    openai: 'data/default-user/OpenAI Settings',
  },
  cleanupHint: `Delete files and directories beginning with ${prefix}`,
};

const manifestFileName = chatsOnly ? `${prefix}_chats_patch_manifest.json` : `${prefix}_manifest.json`;
jsonWrite(path.join(outputRoot, manifestFileName), manifest);
console.log(JSON.stringify(manifest, null, 2));

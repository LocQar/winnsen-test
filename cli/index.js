#!/usr/bin/env node
/**
 * Winnsen RS485 Protocol Test CLI
 *
 * Connect your laptop to the Winnsen lock controller via USB-to-RS485 adapter.
 *
 * Usage:
 *   node index.js              — auto-detect port
 *   node index.js COM3         — specify port
 *   node index.js /dev/ttyUSB0 — Linux/Mac
 *
 * Commands (type in the interactive prompt):
 *   open <lock>      — open a lock (1-16)
 *   poll             — read all door states
 *   watch            — auto-poll every 1s (Ctrl+C to stop watching)
 *   station <n>      — change station number (default: 1)
 *   ports            — list available serial ports
 *   help             — show commands
 *   exit             — quit
 */

const { SerialPort } = require('serialport');
const readline = require('readline');

// ============ WINNSEN PROTOCOL ============

const HEADER = 0x90;
const END = 0x03;

function buildOpenCmd(station, lock) {
  return Buffer.from([HEADER, 0x06, 0x05, station, lock, END]);
}

function buildPollCmd(station, mask = 0xFFFF) {
  return Buffer.from([HEADER, 0x07, 0x02, station, mask & 0xFF, (mask >> 8) & 0xFF, END]);
}

function parseOpenResp(buf) {
  if (buf.length < 7) return null;
  if (buf[0] !== HEADER || buf[2] !== 0x85 || buf[6] !== END) return null;
  return { station: buf[3], lock: buf[4], success: buf[5] === 0x01 };
}

function parsePollResp(buf) {
  if (buf.length < 7) return null;
  if (buf[0] !== HEADER || buf[2] !== 0x82 || buf[6] !== END) return null;
  const low = buf[4], high = buf[5];
  const bits = low | (high << 8);
  const doors = {};
  for (let i = 1; i <= 16; i++) doors[i] = ((bits >> (i - 1)) & 1) === 1;
  return { station: buf[3], bits, doors };
}

function toHex(buf) {
  return [...buf].map(b => b.toString(16).toUpperCase().padStart(2, '0')).join(' ');
}

function timestamp() {
  return new Date().toLocaleTimeString('en-US', { hour12: false, fractionalSecondDigits: 3 });
}

// ============ SERIAL COMMS ============

let port = null;
let stationNum = 1;
let rxBuffer = Buffer.alloc(0);
let pendingResolve = null;
let pendingTimeout = null;
let expectedLen = 0;

function sendAndReceive(txBuf, respLen, timeoutMs = 2000) {
  return new Promise((resolve) => {
    rxBuffer = Buffer.alloc(0);
    expectedLen = respLen;

    pendingResolve = (data) => {
      clearTimeout(pendingTimeout);
      pendingResolve = null;
      resolve(data);
    };

    pendingTimeout = setTimeout(() => {
      pendingResolve = null;
      resolve(null);
    }, timeoutMs);

    console.log(`  ${timestamp()}  \x1b[34mTX\x1b[0m  ${toHex(txBuf)}`);
    port.write(txBuf);
  });
}

function onData(data) {
  rxBuffer = Buffer.concat([rxBuffer, data]);
  if (pendingResolve && rxBuffer.length >= expectedLen) {
    const resp = rxBuffer.subarray(0, expectedLen);
    console.log(`  ${timestamp()}  \x1b[32mRX\x1b[0m  ${toHex(resp)}`);
    pendingResolve(resp);
  }
}

// ============ COMMANDS ============

async function cmdOpen(lock) {
  const lockNum = parseInt(lock);
  if (isNaN(lockNum) || lockNum < 1 || lockNum > 16) {
    console.log('  Lock must be 1-16');
    return;
  }
  console.log(`\n  Opening station=${stationNum} lock=${lockNum}...`);
  const resp = await sendAndReceive(buildOpenCmd(stationNum, lockNum), 7);
  if (!resp) {
    console.log(`  \x1b[33mTIMEOUT\x1b[0m — No response from board`);
    return;
  }
  const parsed = parseOpenResp(resp);
  if (!parsed) {
    console.log(`  \x1b[31mINVALID\x1b[0m — Could not parse response`);
  } else if (parsed.success) {
    console.log(`  \x1b[32m✓ DOOR OPENED\x1b[0m  station=${parsed.station} lock=${parsed.lock}`);
  } else {
    console.log(`  \x1b[31m✗ OPEN FAILED\x1b[0m  station=${parsed.station} lock=${parsed.lock}`);
  }
}

async function cmdPoll() {
  const resp = await sendAndReceive(buildPollCmd(stationNum), 7);
  if (!resp) {
    console.log(`  \x1b[33mTIMEOUT\x1b[0m — No response from board`);
    return null;
  }
  const parsed = parsePollResp(resp);
  if (!parsed) {
    console.log(`  \x1b[31mINVALID\x1b[0m — Could not parse response`);
    return null;
  }

  // Visual door grid
  const openDoors = Object.entries(parsed.doors).filter(([, v]) => v).map(([k]) => k);
  console.log('');
  console.log('  ┌──────────────────────────────────────┐');
  for (let row = 0; row < 4; row++) {
    let line = '  │';
    for (let col = 1; col <= 4; col++) {
      const id = row * 4 + col;
      const isOpen = parsed.doors[id];
      const label = String(id).padStart(2);
      if (isOpen) {
        line += ` \x1b[41m\x1b[37m ${label} OPEN \x1b[0m`;
      } else {
        line += ` \x1b[42m\x1b[37m ${label} SHUT \x1b[0m`;
      }
    }
    line += ' │';
    console.log(line);
  }
  console.log('  └──────────────────────────────────────┘');

  if (openDoors.length === 0) {
    console.log('  All doors \x1b[32mCLOSED\x1b[0m');
  } else {
    console.log(`  Open doors: \x1b[31m${openDoors.join(', ')}\x1b[0m`);
  }
  return parsed;
}

async function cmdWatch() {
  console.log('\n  \x1b[33mWatching door states (1s interval). Press Ctrl+C to stop.\x1b[0m\n');
  let prev = {};

  const interval = setInterval(async () => {
    const parsed = await cmdPoll();
    if (parsed) {
      // Detect changes
      for (const [lock, isOpen] of Object.entries(parsed.doors)) {
        if (prev[lock] !== undefined && prev[lock] !== isOpen) {
          if (isOpen) {
            console.log(`  \x1b[31m>>> DOOR ${lock} OPENED <<<\x1b[0m`);
          } else {
            console.log(`  \x1b[32m>>> DOOR ${lock} CLOSED <<<\x1b[0m`);
          }
        }
      }
      prev = { ...parsed.doors };
    }
  }, 1000);

  // Wait for user to press Enter to stop
  return new Promise((resolve) => {
    const stopRl = readline.createInterface({ input: process.stdin });
    stopRl.once('line', () => {
      clearInterval(interval);
      stopRl.close();
      console.log('  Watch stopped.\n');
      resolve();
    });
  });
}

// ============ MAIN ============

async function listPorts() {
  const ports = await SerialPort.list();
  if (ports.length === 0) {
    console.log('  No serial ports found. Plug in your USB-to-RS485 adapter.');
    return null;
  }
  console.log('\n  Available serial ports:');
  ports.forEach((p, i) => {
    console.log(`    [${i}] ${p.path}  ${p.manufacturer || ''}  ${p.vendorId ? `VID:${p.vendorId}` : ''}`);
  });
  return ports;
}

async function main() {
  console.log('\n  \x1b[36m╔═══════════════════════════════════════╗\x1b[0m');
  console.log('  \x1b[36m║   Winnsen RS485 Protocol Test CLI     ║\x1b[0m');
  console.log('  \x1b[36m║   LocQar — Locker Communication Test  ║\x1b[0m');
  console.log('  \x1b[36m╚═══════════════════════════════════════╝\x1b[0m\n');

  // Find port
  let portPath = process.argv[2];

  if (!portPath) {
    const ports = await listPorts();
    if (!ports || ports.length === 0) {
      process.exit(1);
    }
    // Auto-select first port, or let user choose
    if (ports.length === 1) {
      portPath = ports[0].path;
      console.log(`\n  Auto-selected: ${portPath}`);
    } else {
      // Use first USB-serial adapter found
      const usbPort = ports.find(p =>
        p.manufacturer?.toLowerCase().includes('ch34') ||
        p.manufacturer?.toLowerCase().includes('ftdi') ||
        p.manufacturer?.toLowerCase().includes('prolific') ||
        p.manufacturer?.toLowerCase().includes('silicon') ||
        p.vendorId
      );
      portPath = usbPort ? usbPort.path : ports[0].path;
      console.log(`\n  Selected: ${portPath}`);
    }
  }

  // Open serial port
  console.log(`  Connecting to ${portPath} at 9600 baud...`);

  try {
    port = new SerialPort({
      path: portPath,
      baudRate: 9600,
      dataBits: 8,
      stopBits: 1,
      parity: 'none',
      autoOpen: true
    });
  } catch (e) {
    console.error(`  \x1b[31mFailed to open ${portPath}: ${e.message}\x1b[0m`);
    process.exit(1);
  }

  await new Promise((resolve, reject) => {
    port.on('open', () => {
      console.log(`  \x1b[32m✓ Connected!\x1b[0m  Station: ${stationNum}\n`);
      resolve();
    });
    port.on('error', (err) => {
      console.error(`  \x1b[31mSerial error: ${err.message}\x1b[0m`);
      reject(err);
    });
  });

  port.on('data', onData);

  // Interactive prompt
  console.log('  Commands: open <lock>, poll, watch, station <n>, ports, help, exit\n');

  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
    prompt: '  winnsen> '
  });

  rl.prompt();

  rl.on('line', async (line) => {
    const args = line.trim().split(/\s+/);
    const cmd = args[0]?.toLowerCase();

    switch (cmd) {
      case 'open':
      case 'o':
        await cmdOpen(args[1] || '1');
        break;
      case 'poll':
      case 'p':
        await cmdPoll();
        break;
      case 'watch':
      case 'w':
        await cmdWatch();
        break;
      case 'station':
      case 's':
        const n = parseInt(args[1]);
        if (n >= 1 && n <= 255) {
          stationNum = n;
          console.log(`  Station set to ${stationNum}`);
        } else {
          console.log('  Station must be 1-255');
        }
        break;
      case 'ports':
        await listPorts();
        break;
      case 'help':
      case 'h':
      case '?':
        console.log('');
        console.log('  open <lock>    Open a lock door (1-16)');
        console.log('  poll           Read all 16 door states');
        console.log('  watch          Auto-poll every 1s, detect open/close changes');
        console.log('  station <n>    Set station number (1-255, default: 1)');
        console.log('  ports          List available serial ports');
        console.log('  exit           Quit');
        console.log('');
        break;
      case 'exit':
      case 'quit':
      case 'q':
        port.close();
        process.exit(0);
        break;
      case '':
        break;
      default:
        console.log(`  Unknown command: ${cmd}. Type 'help' for commands.`);
    }

    rl.prompt();
  });

  rl.on('close', () => {
    port.close();
    process.exit(0);
  });
}

main().catch(err => {
  console.error(`Fatal: ${err.message}`);
  process.exit(1);
});

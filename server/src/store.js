// 带原子写与串行化写队列的 JSON 文件存储，stats / chat / manifest 三份数据共用。

const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');

/* ==================== JSON 文件存储 ==================== */

/**
 * 带原子写与串行化写队列的 JSON 存储，stats / chat / manifest 三份数据共用。
 *
 * 解决三件事：
 * 1. 原子写：先写临时文件再 rename，进程在写入途中被杀（systemd 重启、断电）
 *    不会在目标文件上留下截断的半截 JSON；
 * 2. 串行化：读-改-写整体排队。目前各处 I/O 是同步的、天然不会交错，
 *    但只要有一处改成异步就会立刻出现并发覆盖，这里提前把边界立住；
 * 3. 损坏保护：解析失败时把原文件改名留档，而不是静默当成空数据继续写——
 *    旧实现在这种情况下会把历史统计整个抹掉。
 */
class JsonStore {
  constructor(file, makeDefaults) {
    this.file = file;
    this.makeDefaults = makeDefaults;
    this.queue = Promise.resolve();
  }

  read() {
    try {
      return JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (e) {
      if (e.code === 'ENOENT') {
        return this.makeDefaults();
      }
      const name = path.basename(this.file);
      const backup = `${this.file}.corrupt-${Date.now()}`;
      try {
        fs.renameSync(this.file, backup);
        console.error(`[store] ${name} 解析失败，已备份到 ${path.basename(backup)}：${e.message}`);
      } catch (renameErr) {
        console.error(`[store] ${name} 解析失败且备份失败：${renameErr.message}`);
      }
      return this.makeDefaults();
    }
  }

  async writeAtomic(data) {
    const tmp = `${this.file}.${process.pid}.tmp`;
    await fsp.mkdir(path.dirname(this.file), { recursive: true });
    await fsp.writeFile(tmp, JSON.stringify(data, null, 2), 'utf8');
    await fsp.rename(tmp, this.file);
  }

  /**
   * 排队执行一次读-改-写。
   * fn 抛异常时调用方能拿到，但队列自身保持 fulfilled——
   * 否则一次异常会让之后所有写入永久 reject，直到进程重启。
   */
  update(fn) {
    const task = this.queue.then(async () => {
      const data = this.read();
      const result = await fn(data);
      await this.writeAtomic(data);
      return result;
    });
    this.queue = task.then(
      () => {},
      () => {}
    );
    return task;
  }
}

module.exports = { JsonStore };

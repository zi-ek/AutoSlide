'use strict';
const crypto = require('crypto');
const { nowCN } = require('./util');
function safeTrackId(value) {
  const id = String(value || '');
  return /^[A-Za-z0-9_-]{1,64}$/.test(id) ? id : null;
}
function trackIdFor(device) {
  return safeTrackId(device.trackId) || safeTrackId(device.deviceId) ||
    'ec_' + crypto.createHash('sha256').update(String(device.ecSerial || device.deviceId)).digest('hex').slice(0, 40);
}
function resolveDevice(stats, deviceId, serial, name) {
  const devices = stats.devices || (stats.devices = []);
  const shadowId = serial ? 'serial:' + serial : '';

  if (serial) {
    const bySerial = devices.find((d) => d.ecSerial === serial);
    // 影子记录 + 这次带了真 deviceId：说明真身出现了，合并过去
    if (bySerial && deviceId && bySerial.deviceId === shadowId) {
      const real = devices.find((d) => d.deviceId === deviceId);
      if (real) {
        real.ecSerial = serial;
        real.trackId = bySerial.trackId || real.trackId || trackIdFor(real);
        if (bySerial.agentOwner) real.agentOwner = bySerial.agentOwner;
        // Keep the live connection state when a later legacy report claims a shadow row.
        real.deviceInfo = real.deviceInfo || {};
        if (bySerial.deviceInfo?.network) real.deviceInfo.network = bySerial.deviceInfo.network;
        for (const key of ['networkUpdatedAt', 'location', 'locationUpdatedAt', 'command']) {
          if (bySerial[key] !== undefined) real[key] = bySerial[key];
        }
        if (!real.adbPort) real.adbPort = bySerial.adbPort;
        devices.splice(devices.indexOf(bySerial), 1);
        return real;
      }
      // 真身还没建档，把影子记录直接改名认领，省得再造一条
      bySerial.trackId = bySerial.trackId || trackIdFor(bySerial);
      bySerial.deviceId = deviceId;
      return bySerial;
    }
    if (bySerial) return bySerial;
  }

  if (deviceId) {
    const byId = devices.find((d) => d.deviceId === deviceId);
    if (byId) {
      if (serial) byId.ecSerial = serial;
      return byId;
    }
  }

  if (!serial) {
    // 只有 deviceId 又没建过档：不凭空造设备，让它先走安装上报
    return null;
  }

  const created = {
    deviceId: deviceId || shadowId,
    ecSerial: serial,
    brand: String(name || '').split(' ')[0] || '',
    model: String(name || '').slice(0, 64),
    firstInstall: nowCN(),
    installs: 0,
    deviceInfo: {},
  };
  devices.push(created);
  return created;
}

module.exports = { resolveDevice, trackIdFor, safeTrackId };

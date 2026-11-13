import fs from 'node:fs'
import path from 'node:path'

const packageJson = JSON.parse(fs.readFileSync('package.json', 'utf8'))
const versionName = process.env.ANDROID_VERSION_NAME || packageJson.version || '0.4.2'
const versionCode = Number(process.env.ANDROID_VERSION_CODE || '7')

if (!Number.isInteger(versionCode) || versionCode < 1) {
  throw new Error(`ANDROID_VERSION_CODE must be a positive integer; received: ${versionCode}`)
}

const gradlePath = path.join('android', 'app', 'build.gradle')
if (!fs.existsSync(gradlePath)) {
  throw new Error(`Cannot find ${gradlePath}. Run npx cap add android first.`)
}

let gradle = fs.readFileSync(gradlePath, 'utf8')
let changedCode = false
let changedName = false

gradle = gradle.replace(/(\bversionCode\s*=\s*|\bversionCode\s+)\d+/, (_match, prefix) => {
  changedCode = true
  return `${prefix}${versionCode}`
})
gradle = gradle.replace(/(\bversionName\s*=\s*|\bversionName\s+)["'][^"']*["']/,  (_match, prefix) => {
  changedName = true
  return `${prefix}"${versionName}"`
})

if (!changedCode || !changedName) {
  throw new Error(`Could not update Android version values in ${gradlePath}`)
}

fs.writeFileSync(gradlePath, gradle)
console.log(`Android version configured: versionCode=${versionCode}, versionName=${versionName}`)

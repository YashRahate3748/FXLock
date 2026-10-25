import { cp, lstat, mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runCommand } from './dev-tools.mjs'

export const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export async function copyDashboard(root = repositoryRoot) {
  const source = path.join(root, 'frontend', 'dist')
  const destination = path.join(root, 'settlement-service', 'src', 'main', 'resources', 'static', 'dashboard')
  for (const [name, directory] of [['index.html', false], ['assets', true]]) {
    const entry = await lstat(path.join(source, name))
    if (entry.isSymbolicLink() || (directory ? !entry.isDirectory() : !entry.isFile())) {
      throw new Error('Build the React dashboard before copying production assets.')
    }
  }
  await mkdir(destination, { recursive: true })
  // Preserve other static resources and old hashed assets; no recursive deletion is needed.
  await cp(path.join(source, 'assets'), path.join(destination, 'assets'), { recursive: true, force: true })
  await cp(path.join(source, 'index.html'), path.join(destination, 'index.html'), { force: true })
}

export async function buildDashboard({ root = repositoryRoot, signal } = {}) {
  await runCommand('npm', ['run', 'build'], { cwd: path.join(root, 'frontend'), signal })
  await copyDashboard(root)
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  buildDashboard().catch(() => { console.error('Dashboard build/copy failed. Check the build output and directory permissions.'); process.exitCode = 1 })
}

// Dev-only: renders every pose with the app's own mascot code (src/mascot.js) for review in the browser preview.
import { fish, POSES, react, setPose } from '../src/mascot.js';

const grid = document.getElementById('grid');
const sizes = ['xs', 'sm', 'md', 'lg'];

for (const pose of Object.keys(POSES)) {
  const card = document.createElement('button');
  card.type = 'button';
  card.className = 'gallery-card';
  const label = document.createElement('b');
  label.textContent = `${pose}${POSES[pose].loop ? '' : ' · 一次性'}`;
  const row = document.createElement('div');
  row.className = 'gallery-row';
  const fishes = sizes.map((size) => fish(POSES[pose].loop ? pose : 'idle', size));
  row.append(...fishes);
  card.append(label, row);
  card.addEventListener('click', () => fishes.forEach((node) => (POSES[pose].loop ? setPose(node, pose) : react(node, pose))));
  grid.append(card);
}

const root = document.documentElement;
document.querySelector('[data-toggle="theme"]').addEventListener('click', () => { root.dataset.theme = root.dataset.theme === 'dark' ? 'light' : 'dark'; });
document.querySelector('[data-toggle="koi"]').addEventListener('click', () => { root.dataset.koi = root.dataset.koi === 'on' ? 'off' : 'on'; });
document.querySelector('[data-toggle="delight"]').addEventListener('click', () => { root.dataset.delight = root.dataset.delight === 'off' ? 'on' : 'off'; });

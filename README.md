# BlockVault

![img](https://i.imgur.com/DPcMoaq.png)

BlockVault is an interactive challenge where players collaborate to collect every unique block and item in Minecraft. The centerpiece is a large, dynamic museum building where contributions are tracked, and progress is displayed, turning the collection into an engaging and rewarding experience.

## Features
### Interactive Block Displays
* Sections dedicated to specific block categories (e.g., wood, ores, decorative blocks).
* Blocks and items can be placed on predefined slots with item frames or display stands, indicating where each item belongs and can be contributed to.

### Points System
* Players earn points for each block they unlock and contribute to the museum. 
* The points awarded vary based on the rarity of the block/item:
  * **Common Blocks:** 1 point per block.
  * **Uncommon Blocks:** 5 points per block.
  * **Rare Blocks/Items:** 10+ points per block.
* A running total of points is tracked for each player which can be found using `/bvleaderboard`, contributing to their leaderboard position.

### Tracking Progress
* Leaderboard displays the top contributors and their points when using `/bvleaderboard`.
* A progress bar or percentage indicator shows how close the server is to completing the collection using `/bvprogress`.

### Progress Leaderboard
* Progress can be viewed in real-time using a hologram display, showcasing the top contributors and collection milestones.
* Use the `/bvleaderboard` command to see a detailed leaderboard of top contributors, including points and blocks contributed.

## Commands
* `/bvstart` – Starts the vault challenge (requires `blockvault.start`).
* `/bvsubmit` – Submit the block/item that the player is holding to the vault.
* `/bvprogress` – Displays the overall progress of the vault.
* `/bvleaderboard` – Shows the top contributors and their points.
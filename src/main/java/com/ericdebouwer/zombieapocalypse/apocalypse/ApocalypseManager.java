package com.ericdebouwer.zombieapocalypse.apocalypse;

import com.ericdebouwer.zombieapocalypse.ZombieApocalypse;
import com.ericdebouwer.zombieapocalypse.api.ApocalypseEndEvent;
import com.ericdebouwer.zombieapocalypse.api.ApocalypseStartEvent;
import com.ericdebouwer.zombieapocalypse.config.Message;
import com.ericdebouwer.zombieapocalypse.zombie.ZombieType;
import com.google.common.collect.ImmutableMap;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class ApocalypseManager {

	private File apoFile;
	private FileConfiguration apoConfig;
	
	private final String UNTIL_KEY = ".until";
	private final String MOB_CAP_KEY = ".mobcap";
	
	private final List<ApocalypseWorld> apocalypseWorlds = new ArrayList<>();
	private final Map<String, BukkitTask> apoEnders = new HashMap<>();

	private final ZombieApocalypse plugin;
	
	public ApocalypseManager(ZombieApocalypse plugin){
		this.plugin = plugin;
		
		this.createFile();
		if (apoConfig == null) return;
		
		for (final String key: apoConfig.getKeys(false)){
			long endTime = apoConfig.getLong(key + UNTIL_KEY);
			long now = java.time.Instant.now().getEpochSecond();
			
			if (endTime > 0 && endTime < now) continue; //outdated apocalpyse
			
			int mobCap = apoConfig.getInt(key + MOB_CAP_KEY, Bukkit.getMonsterSpawnLimit());
			this.startApocalypse(key, endTime, mobCap, false);
		}
	}
	
	private void createFile(){
		try {
			apoFile = new File(plugin.getDataFolder(), "apocalypse.yml");
			if (!apoFile.exists()){
				apoFile.getParentFile().mkdirs();
				apoFile.createNewFile();
			}
			apoConfig = YamlConfiguration.loadConfiguration(apoFile);
			
		} catch (IOException e) {
			e.printStackTrace();
			plugin.getLogger().warning("Failed to load apocalypse data! Removed 'apocalypse.yml' and restart the server!");
		}
	}
	
	
	private void saveConfig(){
		try{
			for (ApocalypseWorld world: apocalypseWorlds){
				apoConfig.set(world.getWorldName() + UNTIL_KEY, world.getEndEpochSecond());
				apoConfig.set(world.getWorldName() + MOB_CAP_KEY, world.getMobCap());
			}
			this.apoConfig.save(apoFile);
		} catch (IOException | NullPointerException e){
			e.printStackTrace();
		}
	}
	
	public void onDisable(){
		// 비동기적으로 처리 가능한 부분 분리
		apocalypseWorlds.forEach(apoWorld -> {
			World world = Bukkit.getWorld(apoWorld.getWorldName());
			if (world != null) {
				world.getPlayers().forEach(apoWorld::removePlayer);
			}
		});
		
		// 설정 저장은 동기적으로 처리
		this.saveConfig();
	}
	
	private void addEndDelay(final String worldName, long endTime){
		long now = java.time.Instant.now().getEpochSecond();
		long delay = (endTime-now) * 20;
		
		// 큰 딜레이는 여러 작은 작업으로 분할
		if (delay > 6000) { // 5분 이상인 경우
			BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
				if (!isApocalypse(worldName)) return;
				long currentTime = java.time.Instant.now().getEpochSecond();
				if (currentTime >= endTime) {
					endApocalypse(worldName, true);
				}
			}, 6000, 6000); // 5분마다 체크
			apoEnders.put(worldName, task);
		} else {
			BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, 
				() -> endApocalypse(worldName, true), delay);
			apoEnders.put(worldName, task);
		}
	}
	
	public Optional<ApocalypseWorld> getApoWorld(String worldName){
		return apocalypseWorlds.stream().filter(w -> w.getWorldName().equals(worldName)).findFirst();
	}
	
	public boolean isApocalypse(String worldName){
		return this.getApoWorld(worldName).isPresent();
	}

	public boolean startApocalypse(String worldName, long endTime, boolean broadCast){
		World world = Bukkit.getWorld(worldName);
		int mobCap = world == null ? Bukkit.getMonsterSpawnLimit() : world.getMonsterSpawnLimit();
		return this.startApocalypse(worldName, endTime, mobCap, broadCast);
	}

	public boolean startApocalypse(String worldName, long endTime, int mobCap, boolean broadCast){
		if (isApocalypse(worldName)) return false;
		File potentialWorld = new File(Bukkit.getServer().getWorldContainer(), worldName);
		if (!potentialWorld.exists() || !potentialWorld.isDirectory()) return false;
		
		ApocalypseWorld apoWorld = new ApocalypseWorld(plugin, worldName, endTime, mobCap);

		ApocalypseStartEvent event = new ApocalypseStartEvent(apoWorld);
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()){
			Bukkit.removeBossBar(apoWorld.getBossBar().getKey());
			return true;
		}

		apocalypseWorlds.add(apoWorld);
		
		if (endTime > 0){
			this.addEndDelay(worldName, endTime);
			apoWorld.startCountDown();
		}
		
		World world = Bukkit.getWorld(worldName);
		if (world == null) return true; //not loaded
		
		world.setMonsterSpawnLimit(mobCap);
		
		for (Player player: world.getPlayers()){
			if (broadCast) plugin.getConfigManager().sendMessage(player, Message.START_BROADCAST,  ImmutableMap.of("world_name", worldName));
			apoWorld.addPlayer(player);
		}
		return true;
	}
	
	public void setMobCap(String worldName, int mobCap){
		Optional<ApocalypseWorld> apoWorld = getApoWorld(worldName);
		if (!apoWorld.isPresent()) return;
		apoWorld.get().setMobCap(mobCap);
		
		World world = Bukkit.getWorld(worldName);
		if (world == null) return;
		world.setMonsterSpawnLimit(mobCap);
		
	}
	
	public boolean endApocalypse(String worldName, boolean broadCast){
		Optional<ApocalypseWorld> apoWorld = this.getApoWorld(worldName);
		if (!apoWorld.isPresent()) return false;

		ApocalypseEndEvent event = new ApocalypseEndEvent(apoWorld.get());
		Bukkit.getPluginManager().callEvent(event);
		if (event.isCancelled()){
			return true;
		}

		Optional.ofNullable(apoEnders.remove(worldName)).ifPresent(BukkitTask::cancel);
		apocalypseWorlds.remove(apoWorld.get());
		apoConfig.set(worldName, null);
		apoWorld.get().endCountDown();

		this.saveConfig();
		
		World world = Bukkit.getWorld(worldName);
		if (world == null) return true;

		world.setMonsterSpawnLimit(-1);
		
		for (Player player: world.getPlayers()){
			apoWorld.get().removePlayer(player);
			if (broadCast) plugin.getConfigManager().sendMessage(player, Message.END_BROADCAST, ImmutableMap.of("world_name", worldName));
		}

		if (plugin.getConfigManager().isRemoveZombiesOnEnd()) {
			for (Zombie zombie: world.getEntitiesByClass(Zombie.class)){
				if (zombie.getEquipment() != null && !zombie.getEquipment().getItemInMainHand().getType().isAir()) continue;
				if (!Objects.equals(zombie.getCustomName(), plugin.getZombieFactory().getWrapper(ZombieType.getType(zombie)).getCustomName())) continue;
				zombie.remove();
			}
		}
		return true;
	}

	public void reload(){
		for (ApocalypseWorld apoWorld: apocalypseWorlds){
			apoWorld.reloadBossBar();
		}
	}
}

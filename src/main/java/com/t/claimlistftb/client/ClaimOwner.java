package com.t.claimlistftb.client;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.property.TeamProperties;
import net.minecraft.client.Minecraft;

import java.util.*;

/**
 * Represents a claim owner - either a player or a team.
 */
public class ClaimOwner {

    private final String displayName;
    private final UUID teamId;
    private final boolean isTeam;

    // Lazy-loaded member cache
    private List<String> membersCache = null;

    private ClaimOwner(String displayName, UUID teamId, boolean isTeam) {
        this.displayName = displayName;
        this.teamId = teamId;
        this.isTeam = isTeam;
    }

    /**
     * Create a ClaimOwner for a player.
     */
    public static ClaimOwner forPlayer(String playerName, UUID teamId) {
        return new ClaimOwner(playerName, teamId, false);
    }

    /**
     * Create a ClaimOwner for a team.
     */
    public static ClaimOwner forTeam(String teamName, UUID teamId) {
        return new ClaimOwner(teamName, teamId, true);
    }

    public String getDisplayName() {
        return displayName;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public boolean isTeam() {
        return isTeam;
    }

    /**
     * Get the list of member names for this owner's team.
     * Results are cached to avoid repeated API calls.
     */
    public List<String> getMembers() {
        if (membersCache != null) {
            return membersCache;
        }

        membersCache = loadMembers();
        return membersCache;
    }

    /**
     * Get a formatted string of member names.
     * Returns empty string for single-member teams.
     */
    public String getMemberListString() {
        List<String> members = getMembers();
        if (members.isEmpty() || members.size() == 1) {
            return "";
        }
        return "(" + String.join(", ", members) + ")";
    }

    /**
     * Clear the cached member list.
     */
    public void refreshMembers() {
        membersCache = null;
    }

    /**
     * Load members from the FTB Teams API.
     */
    private List<String> loadMembers() {
        List<String> names = new ArrayList<>();

        try {
            Optional<Team> teamOpt = FTBTeamsAPI.api().getClientManager().getTeamByID(teamId);
            if (teamOpt.isEmpty()) {
                return names;
            }

            Team team = teamOpt.get();
            Set<UUID> memberIds = team.getMembers();

            for (UUID memberId : memberIds) {
                String name = getMemberName(memberId);
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
        } catch (Exception e) {
            // Silently handle errors - member list is not critical
        }

        return names;
    }

    /**
     * Get a member's display name from their UUID.
     */
    private String getMemberName(UUID memberId) {
        // Try to get from FTB Teams (player's personal team has their name)
        try {
            Optional<Team> memberTeam = FTBTeamsAPI.api().getClientManager().getTeamByID(memberId);
            if (memberTeam.isPresent()) {
                String name = memberTeam.get().getProperty(TeamProperties.DISPLAY_NAME);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception e) {
            // Ignore
        }

        // Try to get from online players
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return mc.level.players().stream()
                    .filter(p -> p.getUUID().equals(memberId))
                    .map(p -> p.getName().getString())
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClaimOwner that = (ClaimOwner) o;
        return Objects.equals(teamId, that.teamId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId);
    }

    @Override
    public String toString() {
        return displayName + (isTeam ? " [Team]" : " [Player]");
    }
}

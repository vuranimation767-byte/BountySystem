package dev.bountysystem.bounties;

import java.util.UUID;

/**
 * Immutable bounty record.
 *
 * @param id        unique bounty id (generated; also the storage primary key)
 * @param placer    who paid for and owns the bounty
 * @param target    who the bounty is on
 * @param amount    reward in economy currency; escrowed from the placer when placed
 * @param createdAt when the bounty was accepted
 * @param status    lifecycle status
 */
public record Bounty(UUID id, UUID placer, UUID target, double amount, java.time.Instant createdAt, BountyStatus status) {
}

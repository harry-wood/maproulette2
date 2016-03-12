package org.maproulette.models.dal

import anorm._
import anorm.SqlParser._
import org.maproulette.cache.CacheManager
import org.maproulette.models.{Task, Challenge}
import play.api.db.DB
import play.api.libs.json.JsValue
import play.api.Play.current

/**
  * The challenge data access layer handles all calls for challenges going to the database. Most
  * worked is delegated to the ParentDAL and BaseDAL, but a couple of specific function like
  * insert and update found here.
  *
  * @author cuthbertm
  */
object ChallengeDAL extends ParentDAL[Long, Challenge, Task] {
  // The manager for the challenge cache
  override val cacheManager = new CacheManager[Long, Challenge]
  // The name of the challenge table
  override val tableName: String = "challenges"
  // The name of the table for it's children Tasks
  override val childTable: String = "tasks"
  // The row parser for it's children defined in the TaskDAL
  override val childParser = TaskDAL.parser
  override val childColumns: String = TaskDAL.retrieveColumns

  /**
    * The row parser for Anorm to enable the object to be read from the retrieved row directly
    * to the Challenge object.
    */
  override val parser: RowParser[Challenge] = {
    get[Long]("challenges.id") ~
      get[String]("challenges.name") ~
      get[Option[String]]("challenges.identifier") ~
      get[Long]("challenges.parent_id") ~
      get[Option[Int]]("challenges.difficulty") ~
      get[Option[String]]("challenges.description") ~
      get[Option[String]]("challenges.blurb") ~
      get[Option[String]]("challenges.instruction") map {
      case id ~ name ~ identifier ~ parentId ~ difficulty ~ description ~ blurb ~ instruction =>
        new Challenge(id, name, identifier, parentId, difficulty, description, blurb, instruction)
    }
  }

  /**
    * Inserts a new Challenge object into the database. It will also place it in the cache after
    * inserting the object.
    *
    * @param challenge The challenge to insert into the database
    * @return The object that was inserted into the database. This will include the newly created id
    */
  override def insert(challenge: Challenge): Challenge = {
    cacheManager.withOptionCaching { () =>
      DB.withTransaction { implicit c =>
        SQL"""INSERT INTO challenges (name, identifier, parent_id, difficulty, description, blurb, instruction)
              VALUES (${challenge.name}, ${challenge.identifier}, ${challenge.parent},
                      ${challenge.difficulty}, ${challenge.description}, ${challenge.blurb},
                      ${challenge.instruction}) RETURNING *""".as(parser.*).headOption
      }
    }.get
  }

  /**
    * Updates a challenge. Uses the updatingCache so will first retrieve the object and make sure
    * to update only values supplied by the json. After updated will update the cache as well
    *
    * @param updates The updates in json format
    * @param id The id of the object that you are updating
    * @return An optional object, it will return None if no object found with a matching id that was supplied
    */
  override def update(updates:JsValue)(implicit id:Long): Option[Challenge] = {
    cacheManager.withUpdatingCache(Long => retrieveById) { implicit cachedItem =>
      DB.withTransaction { implicit c =>
        val identifier = (updates \ "identifier").asOpt[String].getOrElse(cachedItem.identifier.getOrElse(""))
        val name = (updates \ "name").asOpt[String].getOrElse(cachedItem.name)
        val parentId = (updates \ "parentId").asOpt[Long].getOrElse(cachedItem.parent)
        val difficulty = (updates \ "difficulty").asOpt[Int].getOrElse(cachedItem.difficulty.getOrElse(Challenge.DIFFICULTY_EASY))
        val description =(updates \ "description").asOpt[String].getOrElse(cachedItem.description.getOrElse(""))
        val blurb = (updates \ "blurb").asOpt[String].getOrElse(cachedItem.blurb.getOrElse(""))
        val instruction =(updates \ "instruction").asOpt[String].getOrElse(cachedItem.instruction.getOrElse(""))
        val updatedChallenge = Challenge(id, name, Some(identifier), parentId,
          Some(difficulty), Some(description), Some(blurb), Some(instruction))

        SQL"""UPDATE challenges SET name = ${updatedChallenge.name},
                                    identifier = ${updatedChallenge.identifier},
                                    parent_id = ${updatedChallenge.parent},
                                    difficulty = ${updatedChallenge.difficulty},
                                    description = ${updatedChallenge.description},
                                    blurb = ${updatedChallenge.blurb},
                                    instruction = ${updatedChallenge.instruction}
              WHERE id = $id RETURNING *""".as(parser.*).headOption
      }
    }
  }

  /**
    * The summary for a challenge is the status with the number of tasks associated with each status
    * underneath the given challenge
    *
    * @param id The id for the challenge
    * @return Map of status codes mapped to task counts
    */
  def getSummary(id:Long) : Map[Int, Int] = {
    DB.withConnection { implicit c =>
      val summaryParser = int("count") ~ get[Option[Int]]("tasks.status") map {
        case count ~ status => status.getOrElse(0) -> count
      }
      SQL"""SELECT COUNT(*) as count, status FROM tasks WHERE parent_id = $id GROUP BY status"""
        .as(summaryParser.*).toMap
    }
  }
}
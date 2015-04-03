import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Source
import net.rfc1149.canape.Couch.StatusError
import net.rfc1149.canape._
import play.api.libs.json._

import scala.concurrent.Future

class DatabaseSpec extends WithDbSpecification("db") {

  import implicits._

  private def insertedId(f: Future[JsValue]): Future[String] = f map { js => (js \ "id").as[String] }

  private def insertedRev(f: Future[JsValue]): Future[String] = f map { js => (js \ "rev").as[String] }

  private def inserted(f: Future[JsValue]): Future[(String, String)] =
    for (id <- insertedId(f); rev <- insertedRev(f)) yield (id, rev)

  "db.delete()" should {

    "be able to delete an existing database" in new freshDb {
      waitForResult(db.delete())
      success
    }

    "fail when we try to delete a non-existing database" in new freshDb {
      waitForResult(db.delete())
      waitForResult(db.delete()) must throwA[StatusError]
    }

  }

  "db.create()" should {

    "be able to create a non-existing database" in new freshDb {
      waitForResult(db.delete())
      waitForResult(db.create())
      success
    }

    "fail when trying to create an existing database" in new freshDb {
      waitForResult(db.create()) must throwA[StatusError]
    }

  }

  "db.insert()" should {

    "be able to insert a new document with an explicit id" in new freshDb {
      waitForResult(insertedId(db.insert(JsObject(Nil), "docid"))) must be equalTo "docid"
    }

    "be able to insert a new document with an explicit id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(JsObject(Nil), "docid", true))) must be equalTo "docid"
    }

    "be able to insert a new document with an implicit id" in new freshDb {
      waitForResult(insertedId(db.insert(JsObject(Nil)))) must be matching "[0-9a-f]{32}"
    }

    "be able to insert a new document with an implicit id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(JsObject(Nil), batch = true))) must be matching "[0-9a-f]{32}"
    }

    "be able to insert a new document with an embedded id" in new freshDb {
      waitForResult(insertedId(db.insert(Json.obj("_id" -> "docid")))) must be equalTo "docid"
    }

    "be able to insert a new document with an embedded id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(Json.obj("_id" -> "docid"), batch = true))) must be equalTo "docid"
    }

    "be able to update a document with an embedded id" in new freshDb {
      waitForResult(insertedId(db.insert(Json.obj("_id" -> "docid")))) must be equalTo "docid"
      val updatedDoc = waitForResult(db("docid")) + ("foo" -> JsString("bar"))
      waitForResult(insertedId(db.insert(updatedDoc))) must be equalTo "docid"
    }

    "be able to update a document with an embedded id in batch mode" in new freshDb {
      waitForResult(insertedId(db.insert(Json.obj("_id" -> "docid")))) must be equalTo "docid"
      val updatedDoc = waitForResult(db("docid")) + ("foo" -> JsString("bar"))
      waitForResult(insertedId(db.insert(updatedDoc, batch = true))) must be equalTo "docid"
    }

    "return a consistent rev" in new freshDb {
      waitForResult(insertedRev(db.insert(JsObject(Nil), "docid"))) must be matching "1-[0-9a-f]{32}"
    }

  }

  "db.apply()" should {

    "be able to retrieve the content of a document" in new freshDb {
      val id = waitForResult(insertedId(db.insert(Json.obj("key" -> "value"))))
      val doc = waitForResult(db(id))
      (doc \ "key").as[String] must be equalTo "value"
    }

    "be able to retrieve an older revision of a document with two params" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Json.obj("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> JsString("newValue"))))
      (waitForResult(db(id, rev)) \ "key").as[String] must be equalTo "value"
    }

    "be able to retrieve an older revision of a document with a params map" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Json.obj("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> JsString("newValue"))))
      (waitForResult(db(id, Map("rev" -> rev))) \ "key").as[String] must be equalTo "value"
    }

    "be able to retrieve an older revision of a document with a params sequence" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Json.obj("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> JsString("newValue"))))
      (waitForResult(db(id, Seq("rev" -> rev))) \ "key").as[String] must be equalTo "value"
    }

  }

  "db.delete()" should {

    "be able to delete a document" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(JsObject(Nil))))
      waitForResult(db.delete(id, rev))
      waitForResult(db(id)) must throwA[StatusError]
    }

    "fail when trying to delete a non-existing document" in new freshDb {
      waitForResult(db.delete("foo", "bar")) must throwA[StatusError]
    }

    "fail when trying to delete a deleted document" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(JsObject(Nil))))
      waitForResult(db.delete(id, rev))
      waitForResult(db.delete(id, rev)) must throwA[StatusError]
    }

    "fail when trying to delete an older revision of a document" in new freshDb {
      val (id, rev) = waitForResult(inserted(db.insert(Json.obj("key" -> "value"))))
      val doc = waitForResult(db(id))
      waitForResult(db.insert(doc + ("key" -> JsString("newValue"))))
      waitForResult(db.delete(id, rev)) must throwA[StatusError]
    }
  }

  "db.bulkDocs" should {

    "be able to insert a single document" in new freshDb {
      (waitForResult(db.bulkDocs(Seq(Json.obj("_id" -> "docid"))))(0) \ "id").as[String] must be equalTo "docid"
    }

    "fail to insert a duplicate document" in new freshDb {
      waitForResult(db.bulkDocs(Seq(Json.obj("_id" -> "docid"))))
      (waitForResult(db.bulkDocs(Seq(Json.obj("_id" -> "docid", "extra" -> "other"))))(0) \ "error").as[String] must be equalTo "conflict"
    }

    "fail to insert a duplicate document at once" in new freshDb {
      (waitForResult(db.bulkDocs(Seq(Json.obj("_id" -> "docid"),
        Json.obj("_id" -> "docid", "extra" -> "other"))))(1) \ "error").as[String] must be equalTo "conflict"
    }

    "accept to insert a duplicate document in batch mode" in new freshDb {
      (waitForResult(db.bulkDocs(Seq(Json.obj("_id" -> "docid"),
        Json.obj("_id" -> "docid", "extra" -> "other")),
        true))(1) \ "id").as[String] must be equalTo "docid"
    }

    "generate conflicts when inserting duplicate documents in batch mode" in new freshDb {
      waitForResult(db.bulkDocs(Seq(Json.obj("_id" -> "docid"),
        Json.obj("_id" -> "docid", "extra" -> "other"),
        Json.obj("_id" -> "docid", "extra" -> "yetAnother")),
        true))
      (waitForResult(db("docid", Map("conflicts" -> "true"))) \ "_conflicts").as[Array[JsValue]] must have size 2
    }

  }

  "db.allDocs" should {

    "return an empty count for an empty database" in new freshDb {
      waitForResult(db.allDocs()).total_rows must be equalTo 0
    }

    "return a correct count when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).total_rows must be equalTo 1
    }

    "return a correct id enumeration when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).ids must be equalTo List("docid")
    }

    "return a correct key enumeration when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).keys[String] must be equalTo List("docid")
    }

    "return a correct values enumeration when an element has been inserted" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      val JsString(rev) = waitForResult(db.allDocs()).values[JsValue].head \ "rev"
      rev must be matching "1-[0-9a-f]{32}"
    }

    "be convertible to an items triple" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      val (id: String, key: String, value: JsValue) = waitForResult(db.allDocs()).items[String, JsValue].head
      (value \ "rev").as[String] must be matching "1-[0-9a-f]{32}"
    }

    "be convertible to an items quartuple in include_docs mode" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      val (id: String, key: String, value: JsValue, doc: Some[JsValue]) =
        waitForResult(db.allDocs(Map("include_docs" -> "true"))).itemsWithDoc[String, JsValue, JsValue].head
      ((value \ "rev").as[String] must be matching "1-[0-9a-f]{32}") &&
        ((value \ "rev") must be equalTo(doc.get \ "_rev")) &&
        ((doc.get \ "key").as[String] must be equalTo "value")
    }

    "not return full docs in default mode" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      waitForResult(db.allDocs()).docs[JsValue] must be equalTo Nil
    }

    "return full docs in include_docs mode" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value")))
      waitForResult(db.allDocs(Map("include_docs" -> "true"))).docs[JsValue].head \ "key" must
        be equalTo JsString("value")
    }

    "return full docs in include_docs mode and option" in new freshDb {
      waitForResult(db.insert(Json.obj("key" -> "value"), "docid"))
      (waitForResult(db.allDocs(Map("include_docs" -> "true"))).docs[JsValue].head \ "key") must
        be equalTo JsString("value")
    }

  }

  "db.compact()" should {
    "return with success" in new freshDb {
      waitForResult(db.compact()) \ "ok" must be equalTo JsBoolean(true)
    }
  }

  "db.ensureFullCommit()" should {
    "return with success" in new freshDb {
      waitForResult(db.ensureFullCommit()) \ "ok" must be equalTo JsBoolean(true)
    }
  }

  "db.changes()" should {
    "represent an empty set" in new freshDb {
      (waitForResult(db.changes()) \ "results").as[List[JsObject]] must beEmpty
    }

    "contain the only change" in new freshDb {
      val id = waitForResult(insertedId(db.insert(Json.obj())))
      (waitForResult(db.changes()) \ "results").as[List[JsObject]].head \ "id" must be equalTo JsString(id)
    }

    "return the change in long-polling state" in new freshDb {
      val changes = db.changes(Map("feed" -> "longpoll"))
      changes.isCompleted must beFalse
      val id = waitForResult(insertedId(db.insert(Json.obj())))
      (waitForResult(changes) \ "results").as[List[JsObject]].head \ "id" must be equalTo JsString(id)
    }
  }

  "db.revs_limit()" should {
    "be settable and queryable" in new freshDb {
      waitForResult(db.revs_limit(1938))
      waitForResult(db.revs_limit()) must be equalTo 1938
    }
  }

  "db.continuousChanges()" should {
    "see the creation of new documents" in new freshDb {
      implicit val materializer = ActorFlowMaterializer(None)
      val changes: Source[JsObject, Unit] = db.continuousChanges()
      val result = changes.map(j => (j \ "id").as[String]).take(3).runFold[List[String]](Nil)(_ :+ _)
      db.insert(JsObject(Nil), "docid1")
      db.insert(JsObject(Nil), "docid2")
      db.insert(JsObject(Nil), "docid3")
      waitForResult(result).sorted must be equalTo List("docid1", "docid2", "docid3")
    }

    "reconnect automatically" in new freshDb {
      implicit val materializer = ActorFlowMaterializer(None)
      val changes: Source[JsObject, Unit] = db.continuousChanges(Map("timeout" -> "100"))
      val result = changes.map(j => (j \ "id").as[String]).take(3).runFold[List[String]](Nil)(_ :+ _)
      db.insert(JsObject(Nil), "docid1")
      Thread.sleep(200)
      db.insert(JsObject(Nil), "docid2")
      db.insert(JsObject(Nil), "docid3")
      waitForResult(result).sorted must be equalTo List("docid1", "docid2", "docid3")
    }

    "be able to filter changes" in new freshDb {
      implicit val materializer = ActorFlowMaterializer(None)
      val filter = """function(doc, req) { return doc.name == "foo"; }"""
      waitForResult(db.insert(Json.obj("filters" -> Json.obj("namedfoo" -> filter)), "_design/common"))
      val changes: Source[JsObject, Unit] = db.continuousChanges(Map("filter" -> "common/namedfoo"))
      val result = changes.map(j => (j \ "id").as[String]).take(2).runFold[List[String]](Nil)(_ :+ _)
      waitForResult(db.insert(Json.obj("name" -> "foo"), "docid1"))
      waitForResult(db.insert(Json.obj("name" -> "bar"), "docid2"))
      waitForResult(db.insert(Json.obj("name" -> "foo"), "docid3"))
      waitForResult(db.insert(Json.obj("name" -> "bar"), "docid4"))
      waitForResult(result).sorted must be equalTo List("docid1", "docid3")
    }
  }

  "db.update" should {

    def installUpdate(db: Database) = {
      val update = """
                     | function(doc, req) {
                     |   var newdoc = JSON.parse(req.form.json);
                     |   newdoc._id = req.id || req.uuid ;
                     |   if (doc && doc._rev)
                     |     newdoc._rev = doc._rev;
                     |   return [newdoc, {
                     |     headers : {
                     |      "Content-Type" : "application/json"
                     |      },
                     |     body: JSON.stringify(newdoc)
                     |   }];
                     | };
                   """.stripMargin
      waitForResult(db.insert(Json.obj("updates" -> Json.obj("upd" -> update)), "_design/common"))
    }

    "properly encode values" in new freshDb {
      installUpdate(db)
      val newDoc = waitForResult(db.update("common", "upd", "docid", Map("json" -> Json.stringify(Json.obj("foo" -> "bar")))))
      newDoc \ "_id" must be equalTo JsString("docid")
      newDoc \ "_rev" must beAnInstanceOf[JsUndefined]
      newDoc \ "foo" must be equalTo JsString("bar")
    }

    "properly insert documents" in new freshDb {
      installUpdate(db)
      waitForResult(db.update("common", "upd", "docid", Map("json" -> Json.stringify(Json.obj("foo" -> "bar")))))
      val newDoc = waitForResult(db("docid"))
      newDoc \ "_id" must be equalTo JsString("docid")
      (newDoc \ "_rev").as[String] must startWith("1-")
      newDoc \ "foo" must be equalTo JsString("bar")
    }

    "properly update documents" in new freshDb {
      installUpdate(db)
      waitForResult(db.update("common", "upd", "docid", Map("json" -> Json.stringify(Json.obj("foo" -> "bar")))))
      waitForResult(db.update("common", "upd", "docid", Map("json" -> Json.stringify(Json.obj("foo2" -> "bar2")))))
      val updatedDoc = waitForResult(db("docid"))
      updatedDoc \ "_id" must be equalTo JsString("docid")
      (updatedDoc \ "_rev").as[String] must startWith("2-")
      updatedDoc \ "foo" must beAnInstanceOf[JsUndefined]
      updatedDoc \ "foo2" must be equalTo JsString("bar2")
    }
  }

}

package razie.diesel.ext

import org.bson.types.ObjectId
import razie.Logging
import razie.diesel.dom.RDOM.O
import razie.diesel.dom.{AstKinds, DomAst, RDomain, WikiDomain}
import razie.diesel.engine.{DieselAppContext, DomEngineSettings, InfoNode}
import razie.diesel.utils.{DomUtils, SpecCache}
import razie.tconf.{DSpec, TSpecPath}
import razie.wiki.admin.Autosave
import razie.wiki.model._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** engine prep utilities: load stories, parse DOMs etc */
object EnginePrep extends Logging {

  /** load all stories for reactor, either drafts or final and return the list of wikis */
  def loadStories(settings: DomEngineSettings, reactor: String, userId: Option[ObjectId], storyWpath: String) = {
    val uid = userId.getOrElse(new ObjectId())
    val pages =
      if (settings.blenderMode) {
        val list =
          settings.tagQuery.map { tagQuery =>
            // todo how can we optimize for large reactors: if it starts with "story" use the Story category?
            val tq = new TagQuery(tagQuery)

            // todo optimize
            val wl = (if (tq.ltags.contains("Story"))
              catPages("Story", reactor)
            else
              Wikis(reactor).pages("*")
              )

            val x = wl.toList

            x.filter(w => tq.matches(w.tags))
          } getOrElse {
            // todo optimize
            //            Wikis(reactor).pages("Story").toList
            Wikis(reactor).pages("*").filter(w => w.tags.contains("story")).toList
          }

        val maybeDrafts = list.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            val c = Autosave.find("wikie", p.wid.defaultRealmTo(reactor), uid).flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        maybeDrafts
      } else {
        val spw = WID.fromPath(storyWpath).flatMap(_.page).map(_.content).getOrElse(DomUtils.SAMPLE_SPEC)
        val specName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("wikie", WID.fromPathWithRealm(storyWpath, reactor).get, uid, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Story", specName, specName, "md", spec, uid, Seq("dslObject"), reactor)
        List(page)
      }
    pages
  }

  /** all pages of category
    * - inherit ALL specs from mixins
    */
  def catPages (cat:String, realm:String): List[WikiEntry] = {
    if("Spec" == cat) {
      val w = Wikis(realm)
      val l = (w.pages(cat).toList ::: w.mixins.flatten.flatMap(_.pages(cat).toList))
      // distinct in order - so I overwrite mixins
      val b = ListBuffer[WikiEntry]()
      val seen = mutable.HashSet[WikiEntry]()
      for (x <- l) {
        if (!seen.exists(y=>y.category == x.category && y.name == x.name)) {
          b append x
          seen += x
        }
      }
      b.toList
    } else {
      Wikis(realm).pages(cat).toList
    }
  }

  /**
    * prepare an engine, loading an entire reactor/realm
    *
    * globalStory is dumped at root level, the other stories are scoped
    */
  def prepEngine(id: String,
                 settings: DomEngineSettings,
                 reactor: String,
                 iroot: Option[DomAst],
                 justTests: Boolean,
                 au: Option[WikiUser],
                 description:String,
                 startStory:Option[WikiEntry]=None,
                 useTheseStories: List[WikiEntry] = Nil,
                 endStory:Option[WikiEntry]=None,
                 addFiddles: Boolean = false) = {
    val uid = au.map(_._id).getOrElse(new ObjectId())

    // is there a current fiddle in this reactor/user?
    val wids = Autosave.OR("DomFidPath", WID("","").r(reactor), uid, Map(
      "specWpath" -> """""",
      "storyWpath" -> """"""
    ))

    var storyWpath = wids("storyWpath")

    val stw =
      WID
        .fromPath(storyWpath)
        .flatMap(_.page)
        .map(_.content)
        .getOrElse(DomUtils.SAMPLE_STORY)

    val storyName = WID.fromPath(storyWpath).map(_.name).getOrElse("fiddle")

    val story = Autosave.OR("wikie", WID.fromPathWithRealm(storyWpath, reactor).get, uid, Map(
      "content" -> stw
    )).apply("content")

    val id = java.lang.System.currentTimeMillis().toString()

    val pages =
      if (settings.blenderMode) { // blend all specs and stories
        val d = catPages("Spec", reactor).toList.map { p =>
          //         if draft mode, find the auto-saved version if any
          if (settings.draftMode) {
            // todo uid here is always anonymous - do we use the reactor owner as default?
            val a = Autosave.find("wikie", p.wid.defaultRealmTo(reactor), uid)
            val c = a.flatMap(_.get("content")).mkString
            if (c.length > 0) p.copy(content = c)
            else p
          } else p
        }
        d
      } else {
        var specWpath = wids("specWpath")
        val spw = WID.fromPath(specWpath).flatMap(_.page).map(_.content).getOrElse(DomUtils.SAMPLE_SPEC)
        val specName = WID.fromPath(specWpath).map(_.name).getOrElse("fiddle")
        val spec = Autosave.OR("wikie", WID.fromPathWithRealm(specWpath,reactor).get, uid, Map(
          "content" -> spw
        )).apply("content")

        val page = new WikiEntry("Spec", specName, specName, "md", spec, uid, Seq("dslObject"), reactor)
        List(page)
      }

    // from all the stories, we need to extract all the spec fiddles and add to the dom
    val specFiddles = useTheseStories.flatMap { p =>
      // add sections - for each fake a page
      // todo instead - this shold be in RDExt.addStoryToAst with the addFiddles flag
      p :: sectionsToPages(p, p.sections.filter(s => s.stype == "dfiddle" && (Array("spec") contains s.signature)))
    }

    // finally build teh entire fom

    val dom = (pages ::: specFiddles).flatMap(p =>
      SpecCache.orcached(p, WikiDomain.domFrom(p)).toList
    ).foldLeft(
      RDomain.empty
    )((a, b) => a.plus(b)).revise.addRoot


    //    stimer snap "2_parse_specs"

    val ipage = new WikiEntry("Story", storyName, storyName, "md", story, uid, Seq("dslObject"), reactor)
    val idom = WikiDomain.domFrom(ipage).get.revise addRoot

    //    stimer snap "3_parse_story"

    val root = iroot.getOrElse(DomAst("root", "root"))

    val stories = if (!useTheseStories.isEmpty) useTheseStories else List(ipage)
    startStory.map { we =>
      logger.debug("PrepEngine globalStory:\n"+we.content)
      // main story adds to root, no scope wrappers - this is globals
      addStoryToAst(root, List(we), false, false, false)
    }

    addStoryToAst(root, stories, justTests, false, addFiddles)

    endStory.map { we =>
      logger.debug("PrepEngine endStory:\n"+we.content)
      // main story adds to root, no scope wrappers - this is globals
      addStoryToAst(root, List(we), false, false, false)
    }

    // start processing all elements
    val engine = DieselAppContext.mkEngine(dom, root, settings, ipage :: pages map WikiDomain.spec, description )
    //    engine.ctx._hostname = settings.node
    //    setHostname(engine.ctx)

    engine
  }

  /* extract more nodes to run from the story - add them to root */
  def sectionsToPages(story: WikiEntry, sections: List[WikiSection]): List[WikiEntry] = {
    sections
      .map { sec =>
        val newPage = new WikiEntry(story.wid.cat, story.wid.name + "#" + sec.name, "fiddle", "md",
          sec.content,
          story.by, Seq("dslObject"), story.realm)
        newPage
      }
  }

  /* extract more nodes to run from the story - add them to root */
  def addStoryToAst(root: DomAst, stories: List[WikiEntry], justTests: Boolean = false, justMocks: Boolean = false, addFiddles: Boolean = false) = {

    val allStories = if (!addFiddles) {
      stories
    } else {
      stories.flatMap { story =>
        // add sections - for each fake a page
        // todo instead - this shold be in RDExt.addStoryToAst with the addFiddles flag
        story :: sectionsToPages(story, story.sections.filter(s => s.stype == "dfiddle" && (Array("story") contains s.signature)))
      }
    }

    addStoriesToAst(root, allStories, justTests, justMocks, addFiddles)
  }

  /** nice links to stories in AST trees */
  case class StoryNode (path:TSpecPath) extends CanHtml with InfoNode {
    def x = s"""<a id="${path.wpath.replaceAll("^.*:", "")}"></a>""" // from wpath leave just name
    override def toHtml = x + s"""Story ${path.ahref.mkString}"""
    override def toString = "Story " + path.wpath
  }

  /* add a message */
  def addMsgToAst(root: DomAst, v : EMsg) = {
    root.children append DomAst(v, AstKinds.RECEIVED)
  }

  /**
    *  add all nodes from story and add them to root
    *
    *  todo when are expressions evaluated?
    */
  def addStoriesToAst(root: DomAst, stories: List[DSpec], justTests: Boolean = false, justMocks: Boolean = false, addFiddles:Boolean=false) = {
    var lastMsg: Option[EMsg] = None
    var lastMsgAst: Option[DomAst] = None
    var lastAst: List[DomAst] = Nil
    var inSequence = true

    def addMsg(v: EMsg) = {
      lastMsg = Some(v);
      // withPrereq will cause the story messages to be ran in sequence
      lastMsgAst = if (!(justTests || justMocks)) Some(DomAst(v, AstKinds.RECEIVED).withPrereq({
        if (inSequence) lastAst.map(_.id)
        else Nil
      })) else None // need to reset it
      lastAst = lastMsgAst.toList
      lastAst
    }

    // if the root already had something, we'll continue sequentially
    // this is important for diesel.guardian.starts + diese.setEnv - otherwise tehy run after the tests
    lastAst = root.children.toList

    def addStory (story:DSpec) = {
      var savedInSequence = inSequence

      story.parsed

      if(stories.size > 1 || addFiddles)
        root.children appendAll {
          lastAst = List(DomAst(StoryNode(story.specPath), AstKinds.STORY).withPrereq(lastAst.map(_.id)))
          lastAst
        }

//      RDomain.domFilter(story) {
//        case x@_ => trace("---- "+x)
//      }

      if(stories.size > 1) root.children appendAll addMsg(EMsg("diesel.scope", "push"))

      root.children appendAll RDomain.domFilter(story) {
        case o: O if o.name != "context" => List(DomAst(o, AstKinds.RECEIVED))
        case v: EMsg if v.entity == "ctx" && v.met == "storySync" => {
          inSequence = true
          Nil
        }
        case v: EMsg if v.entity == "ctx" && v.met == "storyAsync" => {
          inSequence = false
          Nil
        }
        case v: EMsg => addMsg(v)
        case v: EVal => {
          // vals are also in sequence... because they use values in context
          lastAst = List(DomAst(v, AstKinds.RECEIVED).withPrereq(lastAst.map(_.id)))
          lastAst
        }
        case e: ExpectM if (!justMocks) => {
          lastAst = List(DomAst(e.withGuard(lastMsg.map(_.asMatch)).withTarget(lastMsgAst), "test").withPrereq(lastAst.map(_.id)))
          lastAst
        }
        case e: ExpectV if (!justMocks) => {
          lastAst = List(DomAst(e.withGuard(lastMsg.map(_.asMatch)).withTarget(lastMsgAst), "test").withPrereq(lastAst.map(_.id)))
          lastAst
        }
        case e: ExpectAssert if (!justMocks) => {
          lastAst = List(DomAst(e.withGuard(lastMsg.map(_.asMatch)).withTarget(lastMsgAst), "test").withPrereq(lastAst.map(_.id)))
          lastAst
        }
        // these don't wait - they don't run, they are collected together
        // todo this is a bit inconsistent - if one declares vals and then a mock then a val
        case v: ERule => List(DomAst(v, AstKinds.RULE))
        case v: EMock => List(DomAst(v, AstKinds.RULE))
      }.flatten

      if(stories.size > 1) root.children appendAll addMsg(EMsg("diesel.scope", "pop"))

      inSequence = savedInSequence
    }

    stories.foreach (addStory)
  }

  /**
    * a simple strToDom parser
    */
  def strToDom[T] (str: String)(p:PartialFunction[Any,T]) : List[T] = {
    val story = new WikiEntry("Story", "temp", "temp", "md", str, new ObjectId(), Seq("dslObject"), "")

    story.parsed

    RDomain.domFilter(story) (p)
  }

  def msgFromStr (realm:String, s:String) = {
    strToDom (s) {
      case m : EMsg => m
    }
  }
}
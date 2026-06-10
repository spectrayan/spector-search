import argparse
import os
import json
import datetime
import random

# Thompson family dataset orchestrator & generator
# Preserves existing corpus-day-01.jsonl and corpus-day-02.jsonl
# Generates Days 3-300+ and biographical memories procedurally
# Builds entities.jsonl, hebbian_edges.jsonl, temporal_chains.jsonl
# Generates queries.jsonl and qrels.tsv targeting the corpus
# Validates the output files and creates validation-report.txt

parser = argparse.ArgumentParser(description="Thompson family dataset orchestrator & generator")
parser.add_argument("--mode", type=str, choices=["balanced", "adhd"], default="adhd", help="Dataset generation mode")
args = parser.parse_args()

GENERATE_NEURODIVERGENT = (args.mode == "adhd")

if GENERATE_NEURODIVERGENT:
    DATASET_DIR = "datasets/cognitive-benchmark"
    DAYS_TO_GENERATE = 365  # 365 days (Day 1 & 2 loaded, Day 3 to 365 generated)
else:
    DATASET_DIR = "datasets/cognitive-benchmark-balanced"
    DAYS_TO_GENERATE = 365  # 365 days (Day 1 & 2 loaded, Day 3 to 365 generated)

ROSTER = {
    "Mike Thompson": "PERSON",
    "Sarah Thompson": "PERSON",
    "Ethan Thompson": "PERSON",
    "Lily Thompson": "PERSON",
    "Tom Thompson": "PERSON",
    "Linda Thompson": "PERSON",
    "Patricia Morales": "PERSON",
    "Robert Morales": "PERSON",
    "Greg Holloway": "PERSON",
    "Anika Patel": "PERSON",
    "Dave Nguyen": "PERSON",
    "Kim Nguyen": "PERSON",
    "Marcus Nguyen": "PERSON",
    "Jake Morrison": "PERSON",
    "Mrs. Patterson": "PERSON",
    "Vertex Health": "ORGANIZATION",
    "CareConnect": "SOFTWARE",
    "Liberty Elementary": "ORGANIZATION",
    "Little Stars Daycare": "ORGANIZATION",
    "Frisco FC": "ORGANIZATION",
    "Frisco Aquatic Center": "LOCATION",
    "Jarvis": "SOFTWARE",
    "Rocky Mountain National Park": "LOCATION",
    "Naperville": "LOCATION",
    "Austin": "LOCATION",
    "Frisco": "LOCATION",
    "Plano": "LOCATION",
    "First United Mortgage": "ORGANIZATION",
    "MedFuture Systems": "ORGANIZATION"
}

DOMAINS = [
    "WORK", "KIDS_SCHOOL", "KIDS_ACTIVITIES", "PARENTING", "HOUSEHOLD",
    "MEAL_PLANNING", "FAMILY_EXTENDED", "SHOPPING", "FINANCE", "HEALTH",
    "SOCIAL", "TRIPS_PLANNING", "HOME_PROJECTS", "PERSONAL"
]
if GENERATE_NEURODIVERGENT:
    DOMAINS.append("NEURODIVERGENT")

# Standard ICNU ranges per domain from SKILL.md
ICNU_RANGES = {
    "WORK": {"interest": (0.4, 0.9), "challenge": (0.3, 0.8), "urgency": (0.3, 1.0), "valence": (-80, 60), "arousal": (40, 180)},
    "KIDS_SCHOOL": {"interest": (0.5, 0.8), "challenge": (0.2, 0.5), "urgency": (0.2, 0.8), "valence": (-40, 80), "arousal": (30, 120)},
    "KIDS_ACTIVITIES": {"interest": (0.6, 0.9), "challenge": (0.1, 0.3), "urgency": (0.1, 0.5), "valence": (20, 100), "arousal": (30, 130)},
    "PARENTING": {"interest": (0.6, 0.9), "challenge": (0.3, 0.7), "urgency": (0.2, 0.9), "valence": (-60, 100), "arousal": (40, 200)},
    "HOUSEHOLD": {"interest": (0.2, 0.5), "challenge": (0.1, 0.4), "urgency": (0.1, 0.6), "valence": (-30, 30), "arousal": (10, 80)},
    "MEAL_PLANNING": {"interest": (0.3, 0.6), "challenge": (0.1, 0.3), "urgency": (0.2, 0.5), "valence": (-10, 50), "arousal": (10, 60)},
    "FAMILY_EXTENDED": {"interest": (0.5, 0.8), "challenge": (0.1, 0.3), "urgency": (0.1, 0.4), "valence": (-30, 80), "arousal": (20, 100)},
    "SHOPPING": {"interest": (0.2, 0.5), "challenge": (0.1, 0.2), "urgency": (0.1, 0.5), "valence": (-20, 40), "arousal": (10, 60)},
    "FINANCE": {"interest": (0.4, 0.7), "challenge": (0.3, 0.6), "urgency": (0.3, 0.8), "valence": (-60, 40), "arousal": (40, 150)},
    "HEALTH": {"interest": (0.5, 0.8), "challenge": (0.2, 0.5), "urgency": (0.2, 0.7), "valence": (-50, 60), "arousal": (30, 140)},
    "SOCIAL": {"interest": (0.5, 0.8), "challenge": (0.1, 0.3), "urgency": (0.1, 0.3), "valence": (10, 90), "arousal": (30, 120)},
    "TRIPS_PLANNING": {"interest": (0.6, 0.9), "challenge": (0.2, 0.5), "urgency": (0.1, 0.5), "valence": (20, 100), "arousal": (40, 150)},
    "HOME_PROJECTS": {"interest": (0.5, 0.8), "challenge": (0.3, 0.6), "urgency": (0.1, 0.4), "valence": (-20, 70), "arousal": (20, 100)},
    "PERSONAL": {"interest": (0.5, 0.9), "challenge": (0.1, 0.4), "urgency": (0.0, 0.2), "valence": (10, 80), "arousal": (10, 80)},
    "NEURODIVERGENT": {"interest": (0.85, 1.0), "challenge": (0.5, 0.9), "urgency": (0.0, 0.3), "valence": (-10, 95), "arousal": (100, 220)},
    "GREETING/JOURNAL": {"interest": (0.1, 0.3), "challenge": (0.0, 0.1), "urgency": (0.0, 0.1), "valence": (-10, 40), "arousal": (5, 30)}
}

# Standard tags per domain
TAGS_BY_DOMAIN = {
    "WORK": ["work", "careconnect", "vertex-health", "meeting", "roadmap", "engineering", "deadline"],
    "KIDS_SCHOOL": ["school", "liberty-elementary", "homework", "math", "reading", "science-project"],
    "KIDS_ACTIVITIES": ["soccer", "frisco-fc", "piano", "practice", "coaching", "recital", "swim-lessons"],
    "PARENTING": ["parenting", "bedtime", "tantrum", "screen-time", "sibling-dynamics", "discipline"],
    "HOUSEHOLD": ["smart-home", "jarvis-automation", "appliance", "cleaning", "hvac", "yard-work"],
    "MEAL_PLANNING": ["meal-prep", "grocery-list", "recipe", "dinner-plan", "cooking", "grilling"],
    "FAMILY_EXTENDED": ["tom-linda", "patricia", "robert-memory", "facetime", "family-visit", "grandparents", "naperville", "austin"],
    "SHOPPING": ["amazon", "costco", "target", "returns", "kids-clothes", "birthday-gift"],
    "FINANCE": ["mortgage", "refinance", "budget", "bills", "savings", "tax", "college-fund"],
    "HEALTH": ["doctor", "pediatrician", "running", "exercise", "sleep", "allergy"],
    "SOCIAL": ["neighbors", "nguyen-family", "friends", "bbq", "couples-dinner", "jake"],
    "TRIPS_PLANNING": ["colorado-trip", "rocky-mountain", "denver", "road-trip", "itinerary", "camping", "packing"],
    "HOME_PROJECTS": ["fence-repair", "woodworking", "garage", "painting", "shelving"],
    "PERSONAL": ["podcast", "relaxation", "personal", "hobbies"],
    "NEURODIVERGENT": ["astronomy", "stargazing", "telescope", "artificial-intelligence", "local-llm", "hyperfocus", "coding", "space-science", "research"]
}

# Load Day 1 and 2 to preserve them
existing_memories = []
highest_id = 0
for day_num in (1, 2):
    path = os.path.join("datasets/cognitive-benchmark", f"corpus-day-{day_num:02d}.jsonl")
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    rec = json.loads(line)
                    existing_memories.append(rec)
                    mem_id = int(rec["id"].split("-")[1])
                    if mem_id > highest_id:
                        highest_id = mem_id

print(f"Loaded {len(existing_memories)} existing memories. Highest ID: mem-{highest_id:04d}")

current_id = highest_id + 1

def make_entity_mentions(text):
    mentions = []
    for entity, etype in ROSTER.items():
        if entity in text:
            mentions.append({"name": entity, "type": etype})
    return mentions

def get_slot_timestamp(date, slot, offset_seconds=0):
    dt = datetime.datetime(date.year, date.month, date.day, tzinfo=datetime.timezone.utc)
    if slot == "early-morning":
        hour = 13
    elif slot == "morning":
        hour = 15
    elif slot == "midday":
        hour = 18
    elif slot == "afternoon":
        hour = 21
    else:  # evening
        hour = 23
    dt = dt + datetime.timedelta(hours=hour, seconds=offset_seconds)
    return int(dt.timestamp() * 1000)

def compute_importance(interest, challenge, urgency, is_special=False):
    base = 0.3 * interest + 0.3 * challenge + 0.4 * urgency
    if is_special:
        return round(2.0 + base * 2.0, 2)
    return round(0.2 + base * 1.3, 2)

def apply_conversational_variance(u_body, j_body, domain):
    if domain == "GREETING/JOURNAL":
        return u_body, j_body
        
    u_pref = random.choice([
        "",
        "Jarvis, ",
        "Hey Jarvis, ",
        "Quick update: ",
        "Quick log: ",
        "Just wanted to mention that "
    ])
    
    j_pref = random.choice([
        "",
        "Got it, Mike. ",
        "Understood. ",
        "Logged that. ",
        "Sure thing! ",
        "Interesting. "
    ])
    
    if u_pref:
        if u_pref.endswith("that "):
            u_body = u_body[0].lower() + u_body[1:]
        u_body = u_pref + u_body
        
    if j_pref:
        j_body = j_pref + j_body
        
    return u_body, j_body

# Combinatorial Templates generation bank
combinatorial_templates = {
    "WORK": {
        "openers": [
            "I'm reviewing the {feature} specs for Vertex Health.",
            "Just had a quick look at the CareConnect backlog.",
            "Working on the roadmap slides for Q4.",
            "We need to address some engineering concerns with the team.",
            "Anika and I were mapping out the sprint goals.",
            "Prepping for the stakeholder meeting later today.",
            "Greg is asking for updates on the latest pilot run.",
            "Analyzing the system logs from yesterday's release.",
            "Reflecting on my job transition from MedFuture Systems to Vertex Health.",
            "Writing up user stories for our upcoming sprint."
        ],
        "cores": [
            ("Greg Holloway wants to drop {feature} to save time, but Anika thinks it is essential.",
             "I suggest a phased rollout. Shipping a basic version of {feature} now and iterating later is a good compromise.",
             ["careconnect", "work", "greg-conflict", "feature-priority", "anika"]),
            ("We are hitting major bottleneck issues with {endpoint} under load.",
             "We should implement a connection pool or check the indexing on the underlying tables for {endpoint}.",
             ["careconnect", "work", "engineering"]),
            ("VP Greg Holloway is complaining about the infrastructure costs for CareConnect.",
             "We can present a cost-benefit analysis showing that dropping these optimizations will cost more in support tickets.",
             ["careconnect", "work", "greg-conflict"]),
            ("Anika Patel did a deep competitor analysis showing MyChart has {feature}.",
             "This competitor data is a powerful lever. You can use it to justify keeping {feature} in the MVP.",
             ["careconnect", "work", "anika", "feature-priority", "stakeholder-review"]),
            ("We got beta feedback from clinics suggesting they need {feature} immediately.",
             "We should prioritize these clinic requests. Let's create a dedicated sub-task in the CareConnect backlog.",
             ["careconnect", "work", "feature-priority"]),
            ("The database query on the patient onboarding schema is running very slow.",
             "I recommend analyzing the query execution plan to see if a compound index can speed up patient onboarding.",
             ["careconnect", "work", "engineering"]),
            ("We have a conflict about whether to prioritize security or speed for the launch.",
             "Security must come first for patient data. We can cite HIPAA standards to Greg to resolve the dispute.",
             ["careconnect", "work", "greg-conflict", "feature-priority"]),
            ("Our Twilio integration is throwing {error_code} rate limit errors during signups.",
             "We should implement a queue-based message broker to buffer SMS delivery rather than hitting Twilio directly.",
             ["careconnect", "work", "engineering"]),
            ("Mentoring Anika Patel on how to handle Q4 planning tradeoffs with Greg.",
             "Encourage her to lead the next tradeoff discussion. It's a great growth opportunity for a junior PM.",
             ["work", "mentoring", "anika"]),
            ("I am negotiating my senior PM salary and signing the Vertex Health offer letter.",
             "Negotiation is key. Ensure the relocation package covers the Chicago to Frisco move.",
             ["work", "career-transition", "refinance"]),
            ("Saying goodbye to my old colleagues in Chicago on my last day at MedFuture Systems.",
             "It's important to keep in touch with old colleagues; they are a great professional network.",
             ["work", "career-transition", "friends"])
        ],
        "closers": [
            "I think we need a compromise before we commit.",
            "We need to get this sorted before the Baylor pilot starts.",
            "This could be a blocker for the Vertex Health launch.",
            "I want to make sure the team doesn't burn out.",
            "Let's see if we can optimize the code first.",
            "Greg is not going to like this, but we have to push back.",
            "Hopefully we can align on this during the stakeholder review.",
            "We need to back this up with solid performance metrics.",
            "Anika is doing a great job managing the feedback.",
            "I want to discuss this with you before the sync."
        ],
        "jarvis_openers": [
            "Understood. ", "Got it, Mike. ", "I've noted that. ", "That sounds critical. ", "I can help with that. ",
            "Good focus area. ", "That is a common bottleneck. ", "Let's break this down. ",
            "I checked the recent logs for that. ", "Here is what I recommend. "
        ],
        "jarvis_closers": [
            "Would you like me to draft a summary for Greg?",
            "I can add this optimization to the backlog.",
            "Let's monitor the error rates over the next sprint.",
            "I'll save this reasoning in our engineering scratchpad.",
            "I can prep some talking points for your meeting with Anika.",
            "Should we schedule a calendar slot to do a deep dive?",
            "I'll keep tracking the API performance.",
            "Let's verify this in the staging environment first.",
            "I can compile the competitor metrics if needed.",
            "We should review this with Greg tomorrow morning."
        ]
    },
    "KIDS_SCHOOL": {
        "openers": [
            "Helping Ethan with his homework tonight.",
            "Sarah attended the parent-teacher conference today.",
            "Ethan came home from Liberty Elementary in a great mood.",
            "Ethan is working on a school assignment at the table.",
            "We're talking about Ethan's academic progress."
        ],
        "cores": [
            ("He is stuck on a math word problem involving division.", "Try using visual diagrams to explain remainders. He'll get it quickly.", ["school", "homework", "math", "parenting"]),
            ("Mrs. Patterson says he is reading at a {reading_level} grade level now.", "That's fantastic. We should continue his daily reading routine to support his growth.", ["school", "homework", "reading", "ethan-growth"]),
            ("He wants to build a clay model of Saturn for his science project.", "We have clay and foam rings in the garage storage shelves that will be perfect.", ["school", "science-project", "homework", "garage"]),
            ("He is studying hard for the school spelling bee tomorrow.", "Spelling bees build great vocabulary. I can quiz him on the word list if he wants.", ["school", "homework", "reading"]),
            ("We need to sign his permission slip for the field trip to the science museum.", "I've added the deadline and details to the calendar so we don't forget.", ["school", "liberty-elementary", "parenting"]),
            ("He is asking lots of questions about exoplanet atmospheric compositions.", "That's a great interest. I can summarize the atmosphere of Venus in a kid-friendly way.", ["school", "science-project", "homework"])
        ],
        "closers": [
            "He is really putting in the effort.",
            "It is great to see him so engaged.",
            "We want to make sure he stays motivated.",
            "I'm proud of his curiosity.",
            "He is growing up so fast."
        ],
        "jarvis_openers": [
            "That's wonderful. ", "Great update. ", "I can help with that. ", "He is doing so well. ", "School projects are fun. "
        ],
        "jarvis_closers": [
            "Let me know if he needs any more help.",
            "I've logged this academic update.",
            "I can quiz him whenever he is ready.",
            "I'll update the calendar reminder.",
            "His curiosity is really impressive."
        ]
    },
    "KIDS_ACTIVITIES": {
        "openers": [
            "Ethan has soccer practice with Frisco FC today.",
            "Lily had her gymnastics class at the community center.",
            "Prepping for the weekend activities for the kids.",
            "Ethan is practicing his piano lessons in the living room.",
            "We went to the Frisco Aquatic Center today."
        ],
        "cores": [
            ("Ethan's team played a match and won {score}!", "What a great win for Frisco FC! He's showing real growth on defense.", ["soccer", "frisco-fc", "parenting"]),
            ("Ethan is practicing {song} for the upcoming piano recital.", "He's making fast progress. I can set a practice timer to help him prepare.", ["piano", "recital", "parenting"]),
            ("Lily blew bubbles without crying during her swim lessons.", "That's a major swim milestone! Her confidence is really building.", ["swim-lessons", "lily-milestone", "parenting"]),
            ("We need to pack his shin guards and water bottle for practice.", "I've set a reminder so you have plenty of time for practice pickup.", ["soccer", "frisco-fc", "practice", "parenting"]),
            ("Lily's gymnastics class signup opens online tomorrow.", "I've added the signup time to your alerts so you can secure a spot.", ["swim-lessons", "parenting", "calendar"]),
            ("Ethan is nervous about the soccer tournament in Plano.", "Reassure him that practice is paying off. I've sent the game schedule to Sarah.", ["soccer", "frisco-fc", "parenting"])
        ],
        "closers": [
            "It's a busy schedule but worth it.",
            "The kids are staying active.",
            "Always coordinate calendars for these.",
            "Feeling good about their progress.",
            "It's great to see them having fun."
        ],
        "jarvis_openers": [
            "Sounds exciting! ", "Great progress. ", "I've got the schedule covered. ", "That's a big step. ", "Busy day for sports! "
        ],
        "jarvis_closers": [
            "I'll track the schedule.",
            "He's doing great with Mrs. Patterson.",
            "Would you like me to update the family calendar?",
            "Let's make sure we have their gear packed.",
            "Go Frisco FC!"
        ]
    },
    "PARENTING": {
        "openers": [
            "Lily had a tough time at bedtime tonight.",
            "Sarah and I are discussing screen-time rules for the kids.",
            "Lily refused to eat her dinner today.",
            "Bedtime went very smoothly tonight.",
            "Sarah is planning a playdate for Ethan.",
            "We brought our new golden retriever puppy, Cooper, home today.",
            "Ethan and Lily are playing with Cooper in the backyard."
        ],
        "cores": [
            ("She had a massive tantrum about brushing her teeth.", "Tantrums are tough at age 3. Turning brushing into a game or song might help.", ["parenting", "bedtime", "tantrum", "lily-milestone"]),
            ("Ethan wants to play Roblox instead of doing his reading.", "A 'homework first, then Roblox' rule helps balance screen time.", ["parenting", "screen-time", "school"]),
            ("She was throwing broccoli on the floor demanding fruit snacks.", "Toddler nutrition is a constant negotiation. Blending veggies into sauce works well.", ["parenting", "meal-prep", "tantrum"]),
            ("We discussed positive discipline strategies for toddler tantrums.", "Consistency is key. Establishing clear, warm boundaries is highly effective.", ["parenting", "discipline", "reflection"]),
            ("Ethan and Marcus Nguyen are playing in our backyard.", "Playdates are great for Ethan. I can add snack shopping to the Target list.", ["parenting", "neighbors", "nguyen-family", "playdate"]),
            ("Lily fell asleep immediately holding her stuffed elephant.", "A calm routine makes all the difference. Nice contrast to the tantrums.", ["parenting", "bedtime", "lily-milestone"]),
            ("Cooper had a small accident on the living room rug during crate training.", "Puppy training takes patience. Try using positive reinforcement and regular outdoor breaks.", ["parenting", "puppy-adoption", "cleaning"]),
            ("Lily is trying to teach Cooper how to sit using training treats.", "That's adorable! Cooper is responding well to Lily's positive cues.", ["parenting", "puppy-adoption", "lily-milestone"])
        ],
        "closers": [
            "Parenting is a constant learning process.",
            "We need to stay consistent with them.",
            "Glad we have a plan.",
            "Hope tomorrow is a bit easier.",
            "They keep us on our toes."
        ],
        "jarvis_openers": [
            "Bedtime can be a challenge. ", "Good planning. ", "That sounds like a sweet moment. ", "Toddlers are full of energy. ", "Screen time is always a negotiation. "
        ],
        "jarvis_closers": [
            "I've updated the grocery list.",
            "I can set a Roblox timer if needed.",
            "Let's keep tracking what works.",
            "Parenting is definitely a journey.",
            "Rest well tonight."
        ]
    },
    "HOUSEHOLD": {
        "openers": [
            "The smart-home hub is acting up today.",
            "Checking on some house maintenance items.",
            "The lawn mower is having issues starting.",
            "Doing some cleaning around the house.",
            "The kitchen sink area has a problem."
        ],
        "cores": [
            ("The living room lights aren't responding to commands.", "The smart-home bridge needs a manual power cycle. Unplug it for 10 seconds.", ["smart-home", "jarvis-automation", "household"]),
            ("The HVAC has been running constantly and upstairs is warm.", "A clogged HVAC filter reduces airflow. I'll note it on our maintenance log.", ["household", "hvac", "home-repair"]),
            ("I think the spark plug on the mower is dirty.", "I can look up a quick spark plug cleaning guide for your mower model.", ["household", "yard-work", "appliance"]),
            ("The garbage disposal is jammed with a cherry pit.", "Safety first: turn off power under the sink, then use the hex wrench.", ["household", "appliance", "home-repair"]),
            ("We are planning a garage clean-up day this weekend.", "I'll add heavy-duty storage bins to the Costco list to help organize.", ["household", "garage", "cleaning"]),
            ("I adjusted the automated lighting sensors to save energy.", "Smart move. I've updated the Jarvis automation dim rules after 9 PM.", ["smart-home", "jarvis-automation", "household"])
        ],
        "closers": [
            "Always something to fix.",
            "Glad I caught it early.",
            "Need to keep the house in shape.",
            "It is running much better now.",
            "Will work on this over the weekend."
        ],
        "jarvis_openers": [
            "Home maintenance is a constant cycle. ", "I can help troubleshoot that. ", "Good call on checking that. ", "Automations are great when they work. ", "Let's get that resolved. "
        ],
        "jarvis_closers": [
            "I'll update the home maintenance log.",
            "Let me know if you need any replacement parts ordered.",
            "I can check local hardware store stock.",
            "Safety is always the priority.",
            "The automations are running now."
        ]
    },
    "MEAL_PLANNING": {
        "openers": [
            "Planning our weekly meals today.",
            "Cooking dinner for the kids tonight.",
            "Putting together our grocery list.",
            "Sarah found a new recipe to try.",
            "Prepping school lunches for the week."
        ],
        "cores": [
            ("We need a quick dinner that the kids will actually eat.", "How about taco night? It's fast, and you have beef and shells ready.", ["meal-prep", "dinner-plan", "parenting", "cooking"]),
            ("Grilling steaks and chicken on the deck tonight.", "Perfect grilling weather! I've added hickory chips to the Costco list.", ["meal-prep", "dinner-plan", "cooking", "grilling"]),
            ("We need to stock up on milk, coffee, and chicken.", "I've updated the Costco list and added diapers as we're running low.", ["meal-prep", "grocery-list", "costco"]),
            ("We want to try a home-cooked chicken parmesan on Thursday.", "Recipe saved. I've added marinara and mozzarella to the grocery list.", ["meal-prep", "recipe", "cooking", "dinner-plan"]),
            ("Packing turkey sandwiches and apples for Ethan.", "Looks balanced. I can set a morning reminder to grab it from the fridge.", ["meal-prep", "grocery-list", "parenting"]),
            ("We want to try a new vegetarian chili recipe.", "Great healthy option. I've added beans and canned tomatoes to the list.", ["meal-prep", "recipe", "dinner-plan", "cooking"])
        ],
        "closers": [
            "It helps to plan ahead.",
            "Want to keep meals simple and healthy.",
            "The kids are always hungry.",
            "Looking forward to a good meal.",
            "That should cover us for the week."
        ],
        "jarvis_openers": [
            "Meal planning saves so much time. ", "Yum, that sounds delicious! ", "I've got the grocery list ready. ", "Healthy choices are great. ", "Let's organize the menu. "
        ],
        "jarvis_closers": [
            "I've updated the shared shopping list.",
            "Enjoy the grilling tonight!",
            "I'll set a reminder for lunch prep.",
            "Let me know how the recipe turns out.",
            "Should I check our pantry stock for that?"
        ]
    },
    "FAMILY_EXTENDED": {
        "openers": [
            "Had a nice FaceTime call with my parents.",
            "Sarah is talking to her mom Patricia.",
            "Looking at some old photo albums today.",
            "My dad Tom called to catch up.",
            "Preparing for a family visit soon."
        ],
        "cores": [
            ("Tom and Linda say they miss the kids in Naperville.", "Grandparents love the updates. Tom wants to show Ethan his model trains.", ["tom-linda", "facetime", "family-visit", "grandparents", "naperville"]),
            ("Patricia is having a knee {mri_day} in Austin.", "Anxiety is natural after Robert's illness. I'll find top Austin doctors.", ["patricia", "family-visit", "health", "austin", "robert-memory"]),
            ("Sarah was reflecting on Robert Morales's woodworking advice.", "Robert was a great craftsman. His legacy lives on in your garage shelves.", ["robert-memory", "family-visit", "woodworking", "reflection"]),
            ("Tom was telling me about a history biography he read.", "Tom has great book recommendations. I've saved it to your reading list.", ["tom-linda", "family-visit", "reading"]),
            ("Patricia is driving up from Austin for a visit.", "I've cleared the guest room schedule and added pie ingredients.", ["patricia", "family-visit", "calendar", "parenting"]),
            ("I sent Tom some photos of Ethan's soccer match.", "Tom was a little league coach, so he'll love seeing Ethan's progress.", ["tom-linda", "soccer", "family-visit", "grandparents"])
        ],
        "closers": [
            "It's important to stay connected.",
            "We really miss them.",
            "Family transitions are always meaningful.",
            "Always good talking to them.",
            "Looking forward to seeing them."
        ],
        "jarvis_openers": [
            "FaceTime is great for staying in touch. ", "It's nice to keep those memories alive. ", "I can help coordinate the visit. ", "Extended family is so important. ", "I've updated our reading list. "
        ],
        "jarvis_closers": [
            "I'll keep the calendar updated.",
            "Would you like me to schedule another call?",
            "I can look up those Austin doctors now.",
            "Give them my best!",
            "Enjoy the family time."
        ]
    },
    "SHOPPING": {
        "openers": [
            "Need to order a few things online.",
            "Going to the store later today.",
            "Looking for some replacement items.",
            "Bought some new gear for the kids.",
            "Adding items to our shopping cart."
        ],
        "cores": [
            ("I ordered Dave Nguyen's birthday gift on Amazon.", "Amazon confirmed Friday delivery. You're set for the weekend barbecue.", ["amazon", "shopping", "birthday-gift", "neighbors"]),
            ("I need to return some kids clothes to Target.", "Target returns list updated. I'll alert you when we drive near the store.", ["shopping", "returns", "target"]),
            ("Searching for a heavy-duty garden hose online.", "I found three top-rated garden hoses on Amazon eligible for Prime.", ["shopping", "amazon", "home-projects"]),
            ("I bought some new soccer cleats for Ethan.", "Excellent timing. They should arrive before the Plano tournament.", ["shopping", "amazon", "soccer", "parenting"]),
            ("We need to get some new wood chisels for the garage.", "High-quality chisels are key. I've flagged a carbon steel set in Frisco.", ["shopping", "woodworking", "garage"])
        ],
        "closers": [
            "Glad to get that checked off.",
            "Hope it arrives on time.",
            "Always comparing prices first.",
            "That should resolve the issue.",
            "Let's see if we need anything else."
        ],
        "jarvis_openers": [
            "Online shopping makes it easy. ", "I've updated your shopping list. ", "I can track the deliveries for you. ", "Good tool upgrades are worth it. ", "Target run is always on the list. "
        ],
        "jarvis_closers": [
            "I've added the tracking details.",
            "I'll remind you when you're near Target.",
            "Should I search for more options?",
            "I can verify store hours for you.",
            "Got it ordered."
        ]
    },
    "FINANCE": {
        "openers": [
            "Reviewing our household budget today.",
            "Looking over our mortgage options.",
            "Updating our financial spreadsheets.",
            "Calculating some savings estimates.",
            "Planning for future education costs.",
            "Sarah and I are looking at home listings in Frisco online."
        ],
        "cores": [
            ("First United Mortgage offered {ref_rate}% with zero points.", "A rate of {ref_rate}% is a big drop from 6.5%. I'll calculate your savings.", ["mortgage", "refinance", "finance", "savings"]),
            ("They requested our W-2 and bank statements.", "I have prepared a checklist of documents in your shared folder.", ["mortgage", "refinance", "finance", "savings"]),
            ("Sarah and I are looking to maximize our savings rate.", "Smart move. I've summarized recent expenses so you can find cuts.", ["finance", "budget", "savings"]),
            ("The refi quote saves us about $250 a month.", "That's $3,000 annually. It could cover the Colorado trip or 529 plans.", ["finance", "mortgage", "refinance", "savings"]),
            ("Researching the tax benefits of the Texas 529 plan.", "529 plans offer great tax-free growth. I've archived the plan details.", ["finance", "savings", "parenting"]),
            ("We are comparing school districts in Frisco to find the best neighborhood.", "Liberty Elementary has excellent reviews. The neighborhood near it is highly recommended.", ["finance", "new-house-search", "school"]),
            ("We just signed the mortgage papers and closed on our Frisco house.", "Congratulations! Owning your home in Frisco is a major milestone for the family.", ["finance", "new-house-search", "mortgage"])
        ],
        "closers": [
            "Every bit of savings helps.",
            "Glad we are staying on top of this.",
            "Need to make a final decision soon.",
            "Financial planning is key for the kids.",
            "Will talk this over with Sarah."
        ],
        "jarvis_openers": [
            "Smart financial planning. ", "I've compiled the spreadsheet numbers. ", "Refinancing is a great leverage point. ", "I can run some projections for you. ", "Budget tracking is updated. "
        ],
        "jarvis_closers": [
            "I'll archive the documentation.",
            "I'll update the budget sheet.",
            "Let me know when you want to execute.",
            "I've saved the 529 details.",
            "Ready to calculate more rates."
        ]
    },
    "HEALTH": {
        "openers": [
            "Tracking my weekly fitness metrics.",
            "Took Lily to her medical checkup.",
            "Managing some allergy issues today.",
            "Reviewing my sleep tracker logs.",
            "Went for a run around the neighborhood."
        ],
        "cores": [
            ("I ran 5 miles but my knee felt tight.", "Great run! Remember to stretch your hamstrings to help the knee.", ["running", "exercise", "health", "sleep"]),
            ("The pediatrician says Lily is growing on track.", "Excellent news. The pediatric report is saved in Lily's folder.", ["doctor", "pediatrician", "health", "lily-milestone"]),
            ("Ethan's grass allergies are acting up again.", "I've set a calendar slot for the clinic and added meds refills.", ["doctor", "pediatrician", "health", "allergy"]),
            ("I only got 6 hours of sleep last night.", "Rest is critical. I suggest setting a bedtime reminder for 10 PM.", ["health", "sleep", "running"]),
            ("I hit 18 miles of running this week.", "Great progress! The consistent stretching is keeping you stable.", ["running", "exercise", "health"])
        ],
        "closers": [
            "Health has to be a priority.",
            "Feeling good about my routine.",
            "Hope the allergies calm down.",
            "Need to focus on recovery.",
            "Will keep monitoring the metrics."
        ],
        "jarvis_openers": [
            "Staying active is key. ", "I've logged your vitals. ", "That's good medical feedback. ", "Sleep quality is so important. ", "Great consistency with running. "
        ],
        "jarvis_closers": [
            "I'll save the mileage logs.",
            "The refilling reminder is active.",
            "I'll monitor the sleep metrics tonight.",
            "Keep stretching that knee!",
            "I've saved the checkup notes."
        ]
    },
    "SOCIAL": {
        "openers": [
            "We got invited to a neighbor's house.",
            "Played golf with Jake Morrison today.",
            "Having dinner with our friends tonight.",
            "Hosted a game night at our place.",
            "Planning a street block party soon."
        ],
        "cores": [
            ("Dave Nguyen invited us for a backyard BBQ.", "Fun! Dave loves IPAs. I can recommend some local microbreweries.", ["neighbors", "nguyen-family", "bbq", "friends"]),
            ("My golf driving was terrible but had a great catch-up.", "Jake is a great friend. Did you discuss the Naperville trip?", ["golf", "jake", "friends", "relaxation"]),
            ("We are meeting Dave and Kim at a new restaurant.", "Enjoy! I've set reservation alerts and mapped the route to Plano.", ["social", "neighbors", "nguyen-family", "friends"]),
            ("We played Catan and Dave Nguyen won at the end.", "Catan matches are always competitive. Dave must have rolled well.", ["social", "neighbors", "friends", "relaxation"]),
            ("Sarah and Kim are organizing the Memorial block party.", "Great neighborhood event. I can help organize the signup sheets.", ["social", "neighbors", "planning", "friends"])
        ],
        "closers": [
            "It is nice to unwind with friends.",
            "Good to stay in touch with everyone.",
            "Always a fun neighborhood crowd.",
            "Looking forward to the weekend.",
            "We need to do this more often."
        ],
        "jarvis_openers": [
            "Social breaks are great. ", "I've noted the plans. ", "Neighborhood connections are fun. ", "Catan is always a good time. ", "I've mapped the locations. "
        ],
        "jarvis_closers": [
            "I'll update the calendar alerts.",
            "Enjoy the craft beer!",
            "Let me know if you need any printouts.",
            "I can suggest more board games.",
            "Have a great time!"
        ]
    },
    "TRIPS_PLANNING": {
        "openers": [
            "Looking at plans for our Colorado road trip.",
            "Checking our camping gear in the garage.",
            "Booking hotels for our trip stopovers.",
            "Finishing our packing list for the hike.",
            "Sarah is planning our scenic driving routes."
        ],
        "cores": [
            ("We want to camp at least two nights in the park.", "Rocky Mountain camping is beautiful. We should book Denver sites soon.", ["colorado-trip", "rocky-mountain", "camping", "packing", "road-trip"]),
            ("We need a new four-person tent and sleeping pads.", "Added to the Amazon list. Let's also check the stove burners.", ["colorado-trip", "camping", "packing", "garage"]),
            ("Sarah wants a hotel near the Denver art district.", "Denver art hotels booked. I've sent the check-in details to email.", ["colorado-trip", "denver", "hotel", "road-trip"]),
            ("Making sure we have water, sunscreen, and maps.", "Excellent. I've archived the checklist and added bear spray.", ["colorado-trip", "packing", "camping", "road-trip"]),
            ("She wants scenic routes that avoid I-70 traffic.", "Smart. I can map out alternative state highways with better views.", ["colorado-trip", "road-trip", "itinerary"])
        ],
        "closers": [
            "It's going to be a fun adventure.",
            "Need to make sure we don't forget anything.",
            "Glad we are starting early.",
            "The kids are super excited.",
            "Can't wait to get out there."
        ],
        "jarvis_openers": [
            "Colorado will be amazing! ", "I've got the travel folders organized. ", "Gear preparation is key. ", "Scenic routes are highly recommended. ", "I've updated the packing list. "
        ],
        "jarvis_closers": [
            "I'll verify the reservation links.",
            "I've stored the maps offline.",
            "Let me know when to buy the gear.",
            "I'll track the mountain weather.",
            "Road trip mode is ready!"
        ]
    },
    "HOME_PROJECTS": {
        "openers": [
            "Heavy winds damaged the backyard fence.",
            "Working on home projects this afternoon.",
            "Sarah wants to repaint the kids' playroom.",
            "Building custom shelves in the garage.",
            "Sarah and I are doing some gardening today."
        ],
        "cores": [
            ("I need to pick up some cedar pickets.", "I've compiled a list of local Frisco stores with cedar pickets.", ["fence-repair", "home-repair", "home-projects", "yard-work"]),
            ("I spent hours repairing the fence pickets.", "Sturdy fence is important for keeping Lily safe in the backyard.", ["fence-repair", "home-repair", "home-projects", "yard-work"]),
            ("She is picking out light blue paint samples.", "Added tape, rollers, and drop cloths to the Target list.", ["home-projects", "painting", "parenting"]),
            ("I'm using leftover red oak wood for cabinetry.", "Cabinetry is rewarding. I've retrieved your woodworking drawings.", ["woodworking", "garage", "home-projects"]),
            ("We are planting new flower beds and mulch.", "Mulch is excellent for retaining moisture in hot Texas soil.", ["home-projects", "yard-work", "garden"])
        ],
        "closers": [
            "The house is looking much better.",
            "Satisfying to build things myself.",
            "It is a lot of physical labor.",
            "Glad to have that completed.",
            "Will continue working on this tomorrow."
        ],
        "jarvis_openers": [
            "Home improvements look great. ", "I can lookup the woodworking files. ", "Good project choice! ", "I've updated your hardware list. ", "Sensible maintenance. "
        ],
        "jarvis_closers": [
            "I'll log the home updates.",
            "Let me know if you need tool info.",
            "The backyard looks secure.",
            "I've saved the paint codes.",
            "Nice work!"
        ]
    },
    "PERSONAL": {
        "openers": [
            "Listening to a podcast in the garage.",
            "Setting my fantasy football lineup.",
            "Reading a new book before bed.",
            "Enjoying a cold beer on the deck."
        ],
        "cores": [
            ("It was a really interesting history episode.", "History podcasts are great for garage time. I can suggest tech ones too.", ["podcast", "relaxation", "personal", "hobbies"]),
            ("My starting running back is questionable.", "Injury report checked. Playing your backup is the safer option.", ["fantasy-football", "relaxation", "personal"]),
            ("Trying to unwind after a stressful day.", "Reading is a great way to disconnect. I've set a quiet sleep timer.", ["personal", "reading", "relaxation"]),
            ("The weather tonight in Frisco is perfect.", "You've earned it! Friday deck relaxation is the best way to spend the evening.", ["personal", "craft-beer", "relaxation"])
        ],
        "closers": [
            "It is nice to have some quiet time.",
            "Important to recharge my batteries.",
            "Just taking it easy tonight.",
            "Ready to relax for the weekend."
        ],
        "jarvis_openers": [
            "Personal time is valuable. ", "I've adjusted the local settings. ", "Relaxation mode active. ", "Good way to wrap up the day. "
        ],
        "jarvis_closers": [
            "I'll silence any notification logs.",
            "Enjoy the episode!",
            "I've saved the reading stats.",
            "Let me know if you need anything else."
        ]
    },
    "NEURODIVERGENT": {
        "openers": [
            "Spent hours calibrating the telescope mount.",
            "Got hyperfocused on my workstation coding.",
            "Reading scientific papers late tonight.",
            "Modified the smart-home alert integrations.",
            "Researching rover power systems in depth.",
            "Writing scripts to process image datasets.",
            "Soldering custom sensors in the garage.",
            "Wrote a custom vector memory index tool."
        ],
        "cores": [
            ("I aligned it and tracked Jupiter's moons.", "Jupiter tracking is a classic target! I've saved the coordinate parameters.", ["astronomy", "stargazing", "telescope", "personal", "hobbies"]),
            ("I quantized a local LLM to run in memory.", "Quantizing 70B models is heavy coding. I've saved settings in your scratchpad.", ["artificial-intelligence", "local-llm", "coding", "hyperfocus", "personal"]),
            ("Spectrometry data for K2-18b is fascinating.", "K2-18b atmospheric profiles are a milestone. I'll fetch companion papers.", ["astronomy", "space-science", "research", "hobbies"]),
            ("Lights will pulse red for X-class solar flares.", "Connected NOAA weather APIs to your lights. Neat space coding hack, Mike!", ["smart-home", "jarvis-automation", "coding", "space-science", "hobbies"]),
            ("Curiosity and Perseverance RTG decay rates differ.", "MMRTG heat management is complex. I've archived your propulsion notes.", ["space-science", "research", "podcast", "relaxation"]),
            ("Scraping astrophotography files from Hubble.", "Hubble raw FITS files are great. I'll track the download folder sync.", ["coding", "astronomy", "research", "hobbies"]),
            ("Building humidity sensors for the greenhouse.", "ESP32 sensor soldering is a fun project. I can fetch pinout diagrams.", ["coding", "smart-home", "garage", "hobbies"]),
            ("Wrote a vector search indexing script in Go.", "Go vector search tools are speedy. Let me know if you want validation runs.", ["coding", "artificial-intelligence", "research", "hobbies"])
        ],
        "closers": [
            "My ADHD hyperfocus really kicked in.",
            "Lost track of time, it's 2 AM now.",
            "Love going down these rabbit holes.",
            "Nice to combine space science and coding.",
            "Really satisfying to build this myself."
        ],
        "jarvis_openers": [
            "Space science and tech is a great focus. ", "Your hyperfocus projects are impressive. ", "I've indexed the research notes. ", "That is a clean implementation. ", "I checked the telemetry feeds. "
        ],
        "jarvis_closers": [
            "Remember to get some sleep!",
            "I'll monitor the API alerts.",
            "I can pull more research papers.",
            "I've saved your custom configurations.",
            "Let me know if you want to run testing scripts."
        ]
    }
}

storyline_arcs = {
    # Phase calculations
    "CareConnect": {
        "early": ("architecting the CareConnect signup flow", "signup flow features", "Greg is worried about MVP scoping"),
        "mid": ("discussing CareConnect database queries and scalability", "database design", "Greg is pushing to drop indexing optimization"),
        "late": ("testing CareConnect notification integrations", "notification system", "Greg is complaining about SMS rates")
    },
    "Colorado": {
        "early": ("looking at Denver hotel options", "hotel booking"),
        "mid": ("checking the roof rack for our Colorado camping trip", "roof rack shopping")
    }
}

def get_arc_details(date):
    if date.month in (5, 6):
        return "late"
    elif date.month in (3, 4):
        return "mid"
    else:
        return "early"

def generate_morning_briefing(date, is_weekend, weekday_str):
    u_greetings = [
        f"Good morning Jarvis! What's on the calendar for this {weekday_str}?",
        f"Morning Jarvis. Can you give me a quick briefing for {weekday_str}?",
        f"Hey Jarvis, what does my schedule look like for {weekday_str}?",
        f"Jarvis, quick check-in. What's on the calendar today?",
        f"Good morning Jarvis! Any major events scheduled for today?",
        f"Morning Jarvis. What's my agenda for this {weekday_str}?",
        f"Hey Jarvis, run through my schedule for today, please.",
        f"Jarvis, what are the calendar items for {weekday_str}?",
        f"Morning Jarvis, help me plan my day. What's on the calendar?",
        f"Good morning! Can you pull up today's calendar and reminders?"
    ]
    u_text = random.choice(u_greetings)
    
    if is_weekend:
        agenda_items = [
            "Ethan's soccer practice scheduled",
            "Lily's swim lesson at the aquatic center",
            "Sarah wanting to work on her design course",
            "gardening in the front walkway",
            "neighborhood BBQ at Dave's place",
            "working on the garage cabinetry",
            "FaceTime call with Tom & Linda in Naperville",
            "a quiet dinner with the family"
        ]
        selected_agenda = random.sample(agenda_items, 2)
        j_text = f"Morning Mike! Today is {weekday_str}, {date.strftime('%B %d')}. No work meetings today. You have {selected_agenda[0]} and {selected_agenda[1]}."
    else:
        work_meetings = [
            "sprint planning at 10 AM",
            "a 1:1 with Anika at 2 PM",
            "the CareConnect stakeholder review at 3 PM",
            "an architecture review at 11 AM",
            "design sync with Sarah at noon",
            "backlog grooming with Greg at 1 PM"
        ]
        family_duties = [
            "Lily needs to be picked up from daycare",
            "Ethan has piano with Mrs. Patterson",
            "Ethan's soccer practice at 4 PM",
            "pediatrician checkup for Lily"
        ]
        selected_work = random.choice(work_meetings)
        selected_family = random.choice(family_duties)
        j_text = f"Morning Mike! Today is {weekday_str}, {date.strftime('%B %d')}. You have {selected_work}, and {selected_family}."
        
    return u_text, j_text

def generate_evening_journal(is_weekend, weekday_str, active_domains):
    u_openers = [
        "Jarvis, logging today's journal.",
        "Quick journal entry for tonight.",
        "Time to log today's thoughts, Jarvis.",
        "Hey Jarvis, logging my evening reflection.",
        "Quick update before bed, logging the journal."
    ]
    
    if is_weekend:
        work_thoughts = [
            "Pretty relaxing weekend day.",
            "Productive Saturday fixing things around the house.",
            "Relaxing Sunday spending time with Sarah and the kids.",
            "Good weekend progress on home projects."
        ]
        highlights = {
            "KIDS_ACTIVITIES": "Ethan had fun with soccer, and Lily did great.",
            "PARENTING": "Managed to handle bedtime without too much screaming.",
            "HOUSEHOLD": "Got some yard work done and cleaned up.",
            "SOCIAL": "Had a great catch-up with the neighbors.",
            "MEAL_PLANNING": "Barbecue went well and everyone liked the dinner.",
            "PERSONAL": "Enjoyed a cold craft beer on the deck.",
            "NEURODIVERGENT": "Spent some quality time stargazing with the telescope."
        }
    else:
        work_thoughts = [
            "Busy day at Vertex Health.",
            "CareConnect meetings took up most of the day.",
            "Made good progress on my work tasks today.",
            "Lots of communication with Greg and Anika today."
        ]
        highlights = {
            "WORK": "CareConnect prep is moving along.",
            "KIDS_SCHOOL": "Ethan did great with his school homework.",
            "PARENTING": "Dealt with bedtime tantrums but made it through.",
            "FINANCE": "Got document checklists ready for the mortgage refinance.",
            "HEALTH": "Logged my running mileage and stretched my knee.",
            "NEURODIVERGENT": "Tuned my local LLM settings and fits scrapers."
        }
        
    u_opener = random.choice(u_openers)
    u_work = random.choice(work_thoughts)
    
    found_highlights = [highlights[d] for d in active_domains if d in highlights]
    if len(found_highlights) >= 2:
        selected_highlights = random.sample(found_highlights, 2)
        u_highlight = f"{selected_highlights[0]} {selected_highlights[1]}"
    elif len(found_highlights) == 1:
        u_highlight = found_highlights[0]
    else:
        u_highlight = "Rest of the day went smoothly."
        
    u_text = f"{u_opener} {u_work} {u_highlight} Ready for sleep."
    
    j_summaries = [
        f"Journal logged, Mike. Rest well and I'll see you tomorrow.",
        f"I've saved tonight's journal. Sleep well, Mike.",
        f"Journal summary recorded. Have a peaceful night.",
        f"Logged that for you. See you in the morning, Mike.",
        f"Reflections saved. Rest up for tomorrow!"
    ]
    j_text = random.choice(j_summaries)
    
    return u_text, j_text

generated_records = []

# Generate Day-by-Day (Day 3 to DAYS_TO_GENERATE)
start_date = datetime.date(2025, 6, 5)

for day in range(3, DAYS_TO_GENERATE + 1):
    sim_date_2025 = start_date - datetime.timedelta(days=day - 1)
    sim_date_2026 = datetime.date(2026, 6, 5) - datetime.timedelta(days=day - 1)
    
    is_weekend = sim_date_2025.weekday() >= 5
    weekday_str = sim_date_2025.strftime("%A")
    date_str = sim_date_2025.strftime("%Y-%m-%d")
    date_str_2026 = sim_date_2026.strftime("%Y-%m-%d")
    
    # Roster of sessions
    slots = ["early-morning", "morning", "midday", "afternoon", "evening"]
    
    # Greeting in the morning
    greeting_ts = get_slot_timestamp(sim_date_2025, "early-morning", 0)
    session_id = f"session-{date_str_2026}-early-morning"
    
    u_text, j_text = generate_morning_briefing(sim_date_2025, is_weekend, weekday_str)
        
    u_rec = {
        "id": f"mem-{current_id:04d}",
        "text": u_text,
        "title": "Morning Greeting",
        "synapticTags": ["morning-routine", "greeting", "calendar"],
        "valence": 10,
        "importance": 0.3,
        "arousal": 15,
        "sessionId": session_id,
        "timestampMs": greeting_ts,
        "entityMentions": make_entity_mentions(u_text),
        "memoryType": "EPISODIC",
        "recallCount": 0,
        "interest": 0.2,
        "challenge": 0.0,
        "urgency": 0.2
    }
    current_id += 1
    
    j_rec = {
        "id": f"mem-{current_id:04d}",
        "text": j_text,
        "title": "Jarvis Morning Briefing",
        "synapticTags": ["morning-routine", "calendar"],
        "valence": 10,
        "importance": 0.35,
        "arousal": 15,
        "sessionId": session_id,
        "timestampMs": greeting_ts + 30000,
        "entityMentions": make_entity_mentions(j_text),
        "memoryType": "SEMANTIC",
        "recallCount": 0,
        "interest": 0.2,
        "challenge": 0.1,
        "urgency": 0.2
    }
    current_id += 1
    generated_records.extend([u_rec, j_rec])
    
    # Determine domains for the day
    if is_weekend:
        day_domains = ["KIDS_ACTIVITIES", "PARENTING", "HOUSEHOLD", "SOCIAL", "MEAL_PLANNING", "PERSONAL"]
        if GENERATE_NEURODIVERGENT:
            day_domains.append("NEURODIVERGENT")
    else:
        day_domains = ["WORK", "KIDS_SCHOOL", "WORK", "PARENTING", "FINANCE", "HEALTH"]
        if GENERATE_NEURODIVERGENT:
            day_domains.append("NEURODIVERGENT")
        
    # Generate turns for selected domains
    random.shuffle(day_domains)
    
    # Double the turns per day
    selected_domains = day_domains * 2
    
    # Make a slot mapping dynamically
    slot_mapping = {}
    for idx in range(len(selected_domains)):
        if idx < len(selected_domains) // 4:
            slot_mapping[idx] = "morning"
        elif idx < len(selected_domains) // 2:
            slot_mapping[idx] = "midday"
        elif idx < (3 * len(selected_domains)) // 4:
            slot_mapping[idx] = "afternoon"
        else:
            slot_mapping[idx] = "evening"
    
    for idx, domain in enumerate(selected_domains):
        slot = slot_mapping.get(idx, "evening")
        session_id = f"session-{date_str_2026}-{slot}"
        ts = get_slot_timestamp(sim_date_2025, slot, idx * 300)
        
        options = combinatorial_templates[domain]
        u_opener = random.choice(options["openers"])
        u_closer = random.choice(options["closers"])
        core_idx = random.randint(0, len(options["cores"]) - 1)
        u_core_tmpl, j_advice_tmpl, rec_tags = options["cores"][core_idx]
        
        j_opener = random.choice(options["jarvis_openers"])
        j_closer = random.choice(options["jarvis_closers"])
        
        u_body_tmpl = f"{u_opener} {u_core_tmpl} {u_closer}"
        j_body_tmpl = f"{j_opener}{j_advice_tmpl} {j_closer}"
        
        score = random.choice(["2-1", "3-2", "1-0", "4-3"])
        song = random.choice(["Für Elise", "Minuet in G", "Moonlight Sonata", "Jingle Bells"])
        feature = random.choice(["patient onboarding", "billing integration", "scheduling portal", "secure messaging"])
        endpoint = random.choice(["/sms/send", "/user/verify", "/appointment/notify"])
        ref_rate = random.choice(["5.8", "5.75", "5.65", "5.5"])
        mri_day = random.choice(["next Tuesday", "this Thursday", "Friday morning"])
        reading_level = random.choice(["4th", "5th", "3rd grade advanced"])
        error_code = random.choice(["429", "503", "504", "400"])
        time_limit = random.choice(["30 minutes", "1 hour", "45 minutes"])
        tool = random.choice(["chisels", "hand planes", "saws"])
        dest = random.choice(["Rocky Mountain", "Denver", "Colorado springs"])
        hobby = random.choice(["stargazing", "coding", "stardust analysis"])
        
        u_body = u_body_tmpl.format(score=score, song=song, feature=feature, endpoint=endpoint, 
                                    ref_rate=ref_rate, mri_day=mri_day, reading_level=reading_level,
                                    error_code=error_code, time_limit=time_limit, tool=tool, dest=dest, hobby=hobby)
        j_body = j_body_tmpl.format(score=score, song=song, feature=feature, endpoint=endpoint, 
                                    ref_rate=ref_rate, mri_day=mri_day, reading_level=reading_level,
                                    error_code=error_code, time_limit=time_limit, tool=tool, dest=dest, hobby=hobby)
        
        u_body, j_body = apply_conversational_variance(u_body, j_body, domain)
        
        icnu_lims = ICNU_RANGES[domain]
        interest = round(random.uniform(icnu_lims["interest"][0], icnu_lims["interest"][1]), 2)
        challenge = round(random.uniform(icnu_lims["challenge"][0], icnu_lims["challenge"][1]), 2)
        urgency = round(random.uniform(icnu_lims["urgency"][0], icnu_lims["urgency"][1]), 2)
        valence = random.randint(icnu_lims["valence"][0], icnu_lims["valence"][1])
        arousal = random.randint(icnu_lims["arousal"][0], icnu_lims["arousal"][1])
        
        importance = compute_importance(interest, challenge, urgency, is_special=(valence > 70 or valence < -60))
        
        u_rec = {
            "id": f"mem-{current_id:04d}",
            "text": u_body,
            "title": f"{domain.title().replace('_', ' ')} Update",
            "synapticTags": rec_tags,
            "valence": valence,
            "importance": importance,
            "arousal": arousal,
            "sessionId": session_id,
            "timestampMs": ts,
            "entityMentions": make_entity_mentions(u_body),
            "memoryType": "EPISODIC",
            "recallCount": 0,
            "interest": interest,
            "challenge": challenge,
            "urgency": urgency
        }
        current_id += 1
        
        j_rec = {
            "id": f"mem-{current_id:04d}",
            "text": j_body,
            "title": f"Jarvis {domain.title().replace('_', ' ')} Response",
            "synapticTags": rec_tags,
            "valence": max(-128, min(127, valence + 15)),
            "importance": round(max(0.05, importance - 0.2), 2),
            "arousal": max(0, min(255, arousal - 20)),
            "sessionId": session_id,
            "timestampMs": ts + 30000,
            "entityMentions": make_entity_mentions(j_body),
            "memoryType": "SEMANTIC",
            "recallCount": 0,
            "interest": round(max(0.0, interest - 0.1), 2),
            "challenge": round(max(0.0, challenge - 0.1), 2),
            "urgency": round(max(0.0, urgency - 0.1), 2)
        }
        current_id += 1
        generated_records.extend([u_rec, j_rec])
        
    # 3. Evening Journal entry
    journal_ts = get_slot_timestamp(sim_date_2025, "evening", 600)
    session_id = f"session-{date_str_2026}-evening"
    
    u_text, j_text = generate_evening_journal(is_weekend, weekday_str, selected_domains)
    valence = 35 if is_weekend else 15
        
    u_rec = {
        "id": f"mem-{current_id:04d}",
        "text": u_text,
        "title": "Evening Journal Entry",
        "synapticTags": ["evening-journal", "reflection", "planning"],
        "valence": valence,
        "importance": 0.8,
        "arousal": 25,
        "sessionId": session_id,
        "timestampMs": journal_ts,
        "entityMentions": make_entity_mentions(u_text),
        "memoryType": "EPISODIC",
        "recallCount": 0,
        "interest": 0.3,
        "challenge": 0.1,
        "urgency": 0.1
    }
    current_id += 1
    
    j_rec = {
        "id": f"mem-{current_id:04d}",
        "text": j_text,
        "title": "Jarvis Journal Summary",
        "synapticTags": ["evening-journal", "reflection"],
        "valence": 15,
        "importance": 0.4,
        "arousal": 15,
        "sessionId": session_id,
        "timestampMs": journal_ts + 30000,
        "entityMentions": make_entity_mentions(j_text),
        "memoryType": "SEMANTIC",
        "recallCount": 0,
        "interest": 0.2,
        "challenge": 0.0,
        "urgency": 0.1
    }
    current_id += 1
    generated_records.extend([u_rec, j_rec])
    
    day_file = os.path.join(DATASET_DIR, f"corpus-day-{day:02d}.jsonl")
    day_recs = generated_records[-(len(selected_domains)*2 + 4):]
    with open(day_file, "w", encoding="utf-8") as f:
        for r in day_recs:
            f.write(json.dumps(r) + "\n")

print(f"Generated daily records. Current record count: {len(generated_records)}")

# Generate Biographical memories
print("Generating biographical memories...")
biographical_records = []

if GENERATE_NEURODIVERGENT:
    bio_categories = [
        ("mike_childhood", 60),
        ("mike_school", 60),
        ("mike_early_career", 60),
        ("sarah_childhood", 60),
        ("sarah_school", 60),
        ("marriage_family", 80),
        ("parents_memories", 80),
        ("chicago_to_texas", 80),
        ("robert_passing", 60),
        ("career_transition", 80),
        ("puppy_adoption", 80),
        ("new_house_search", 80),
        ("career_vertex", 80),
        ("formative", 80),
        ("holidays_traditions", 80),
        ("neurodivergence_hyperfocus", 150)
    ]
else:
    bio_categories = [
        ("mike_childhood", 60),
        ("mike_school", 60),
        ("mike_early_career", 60),
        ("sarah_childhood", 60),
        ("sarah_school", 60),
        ("marriage_family", 90),
        ("parents_memories", 90),
        ("chicago_to_texas", 90),
        ("robert_passing", 60),
        ("career_transition", 90),
        ("puppy_adoption", 90),
        ("new_house_search", 90),
        ("career_vertex", 90),
        ("formative", 90),
        ("holidays_traditions", 90)
    ]

bio_templates = {
    "mike_childhood": [
        "I remember when Tom Thompson coached my youth baseball team in Naperville. He always taught us to {lesson}.",
        "On Saturdays, Linda Thompson would take me to the local library in Naperville. We spent hours reading about {topic}.",
        "Building a massive snow fort in the backyard in Naperville. We stayed out in the cold until {condition} and then had {treat}.",
        "My dad Tom and I built model rockets in the garage. We launched them at the park, and one got stuck in {obstacle}.",
        "Linda Thompson helped me study for the spelling bee in middle school. We memorized {subject} together in Naperville.",
        "Sledding down the steep hills at the Naperville park. The walk back up was {feeling}.",
        "Tom Thompson took me to my first major league baseball game in Chicago. We ate {food} and sang during the game.",
        "Our family summer road trip to Wisconsin. I got carsick in the back of our old station wagon but loved {attraction}.",
        "Linda Thompson taught me how to bake cookies on rainy Sunday afternoons in Naperville. The kitchen always smelled like {smell}.",
        "Learning to ride my bike on the sidewalk. Tom held onto the seat and jogged behind me until {milestone} in Naperville."
    ],
    "mike_school": [
        "High school varsity baseball in Naperville. Playing center field and late practices felt {feeling}.",
        "Attending the University of Illinois at Urbana-Champaign. Cramming for {topic} in Grainger Library was {feeling}.",
        "My engineering internship in Chicago. Getting used to the commute was {feeling}.",
        "Living in a cramped dorm room with roommates. We studied {topic} and played video games late.",
        "Graduation day at UIUC. Celebrating with Tom and Linda at the Alma Mater statue was {feeling}.",
        "Making lifelong friends like Jake Morrison. We talked about engineering and our {feeling} career prospects.",
        "Taking a public speaking class. I initially hated it, but it was {feeling} for my future.",
        "Working as a lab assistant in the computer science room, helping freshmen with {topic}.",
        "Joining a recreational soccer league. It was {feeling} and kept me active during college.",
        "Our college road trip to New Orleans, spending hours talking about engineering and {topic}."
    ],
    "mike_early_career": [
        "Working as an associate product manager in Chicago. Mentoring taught me the {lesson}.",
        "Handling releases late at night. Coordinating deployments under pressure taught me the {lesson}.",
        "Navigating conflicts between marketing and engineering. Negotiating taught me the {lesson}.",
        "Attending tech conferences in San Francisco. Meeting industry leads taught me the {lesson}.",
        "Transitioning to health-tech. Learning compliance standards taught me the {lesson}.",
        "Mentoring new APMs in Chicago. Leading sprint reviews taught me the {lesson}.",
        "Learning how to analyze database tables with {tool} in Chicago. Troubleshooting taught me the {lesson}.",
        "Designing analytics dashboards from scratch. Iterating designs with {tool} taught me the {lesson}.",
        "Preparing slides for VP-level presentations in Chicago. Presenting trade-offs taught me the {lesson}.",
        "Commuting on the Chicago L train. Reading blogs on my phone taught me the {lesson}."
    ],
    "sarah_childhood": [
        "Sarah told me about fishing with Robert Morales at Lake Travis in Austin. It involved {detail}.",
        "Sarah's mom Patricia Morales baked pecan pies in Austin, which was her favorite {detail}.",
        "Sarah remembered the hot Austin summers. Running through backyard sprinklers was a sweet {detail}.",
        "Robert Morales built a small wooden playhouse in the Austin backyard. It was a wonderful {detail}.",
        "Patricia Morales working as a nurse in Austin. Hearing hospital stories was an inspiring {detail}.",
        "Sarah's childhood golden retriever named Sandy. Playing with Sandy was a joyful {detail}.",
        "Sarah taking her first art class in Austin. Discovering sketching was a key {detail}.",
        "Family road trips to Big Bend National Park. Camping under the stars was an amazing {detail}.",
        "Patricia Morales teaching Sarah how to garden in Austin, which was a therapeutic {detail}.",
        "Robert Morales helping Sarah build a jewelry box, which was a special {detail}."
    ],
    "sarah_school": [
        "Sarah's years at the School of the Art Institute of Chicago. Refining her portfolio in {studio} was a challenge.",
        "Sarah moving from Austin to Chicago. Navigating the cold weather was a major {challenge}.",
        "Designing her first mobile app user experience. Studying {topic} in Chicago was a massive milestone.",
        "Sarah working at a local Chicago gallery, which helped her learn about {topic}.",
        "Sarah's graduation exhibition in Chicago. Showcasing her wireframes in {studio} was memorable.",
        "Sarah's first freelance UX design client in Chicago. Scoping {topic} was her first big hurdle.",
        "Participating in a design hackathon in Chicago. Solving accessibility and {topic} was a great team effort.",
        "Sarah taking a typography class, spending hours in {studio} studying fonts.",
        "Living in a tiny apartment in Logan Square. Sketching designs for {topic} filled her weekends.",
        "Sarah learning web coding. Building prototypes in {studio} helped her bridge the gap with developers."
    ],
    "marriage_family": [
        "The day Sarah and I met at a wedding in Chicago. Celebrating that night was {feeling}.",
        "Our wedding in Austin. A beautiful outdoor ceremony was a source of {feeling}.",
        "Sarah and I moving into our first home in Naperville. We spent weekends {detail}.",
        "Welcoming our son Ethan Thompson in Chicago. Starting our parenting journey was {feeling}.",
        "Ethan taking his first steps in the living room in Naperville. Seeing him balance was {feeling}.",
        "The birth of our daughter Lily in Texas. Adding her to our family brought {feeling}.",
        "Our first family road trip to the Texas coast. Helping the kids was a source of {feeling}.",
        "Sarah launching her UX design studio. Seeing her build the company was {feeling}.",
        "Moving our family to Frisco, Texas. Setting up the house together was {feeling}.",
        "Coaching Ethan's soccer team in Frisco. Watching him grow brings {feeling}."
    ],
    "parents_memories": [
        "Tom Thompson coaching my baseball team. He modeled the {lesson} for all of us.",
        "Linda Thompson working as a librarian in Naperville. Her work showed a deep {lesson}.",
        "Patricia Morales working as a nurse in Austin. Her dedication showed the {lesson}.",
        "Robert Morales building cedar chairs. His workshop projects modeled the {lesson}.",
        "Tom and Linda visiting us in Texas. Watching them guide the kids was a warm {lesson}.",
        "Patricia Morales teaching Sarah to bake. Her kitchen sessions taught the {lesson}.",
        "Tom Thompson sharing school stories, reflecting on how education models the {lesson}.",
        "Linda Thompson reading to Lily. Her storytelling modeled a great {lesson}.",
        "Robert Morales teaching me woodworking. His patient guidance taught the {lesson}.",
        "Patricia Morales visiting for Lily's birthday. Her thoughtful presence showed the {lesson}."
    ],
    "chicago_to_texas": [
        "The long drive from Chicago to Frisco. We spent days {detail}.",
        "House hunting in Frisco. We loved {places} and finding our neighborhood.",
        "Adjusting to the hot Texas summer. We resolved the transition by {challenges}.",
        "Registering Ethan at Liberty Elementary. Setting up his school was a key transition.",
        "Meeting our neighbors Dave and Kim Nguyen. They welcomed us to Frisco.",
        "Setting up my home office in Frisco. Resolving remote setups was one of our early {challenges}.",
        "Exploring local microbreweries in Plano, checking out {places} with Sarah.",
        "Lily starting at daycare in Plano. Transitioning her routine was one of our major {challenges}.",
        "Buying our first grill in Frisco, using {places} to source it.",
        "Finding a running trail in Frisco. Establishing early morning routines helped resolve our fitness {challenges}."
    ],
    "robert_passing": [
        "Robert Morales diagnosed with cancer. We focused on {details} to support Patricia.",
        "Sarah and Patricia spending months in the Austin hospital, sharing {feelings}.",
        "Supporting Sarah through her grief, focusing on {details} to help her recover.",
        "Helping Patricia with estate paperwork in Austin, which was a source of {feelings}.",
        "Sarah looking at old photos of Robert in his shop, reflecting on his {lessons}.",
        "Sarah inheriting Robert's hand planes. Putting them in our garage workshop kept his {lessons} close.",
        "Patricia Morales living alone in Austin. Supporting her transition required much {feelings}.",
        "Reflecting on Robert's quiet strength and humor. His life modeled key {lessons}.",
        "Sarah designing a custom frame. Her craftsmanship honored Robert's {lessons}.",
        "Visiting Robert's grave in Austin, sharing memories that brought {feelings}."
    ],
    "career_transition": [
        "Reflecting on my job transition from MedFuture Systems to Vertex Health. Negotiating the package was a key {lesson}.",
        "Saying goodbye to Chicago colleagues on my last day. They presented me with a signed card that brought back {feelings}.",
        "Reading my onboarding files for Vertex Health. Setting up my accounts took much {feelings}.",
        "First day at the Plano office. Finding my desk and meeting the senior team was one of my major {challenges}.",
        "Comparing MedFuture's legacy medical software roadmap to Vertex's agile frameworks. Resolving the culture gap took {lessons}."
    ],
    "puppy_adoption": [
        "Adopting our golden retriever puppy, Cooper. Buying puppy food and crate training him was a major {habit}.",
        "Cooper playing with the kids in the backyard. Seeing Ethan throw tennis balls and Cooper chase them brings {feeling}.",
        "Lily teaching Cooper how to sit. Her patience with the training treats was a sweet family {milestone}.",
        "Setting a strict feeding schedule for Cooper. Organizing this routine helped build good {habits} for the kids.",
        "Cooper chewing on my old running shoes in the garage. Resolving his chewing habit required extra chew toys."
    ],
    "new_house_search": [
        "Searching for our home in Frisco. Comparing neighborhoods and school districts online took much {feeling}.",
        "Dealing with the mortgage approvals and closing papers. Getting the final sign-off was a relief after all the {challenges}.",
        "Moving our furniture into our new Frisco home. Spending days unpacking and organizing was a physical {feeling}.",
        "Setting up custom smart lighting in our new living room, optimizing the sensor rules was one of my favorite {habits}.",
        "Planted new cedar privacy trees along our property line, which was a satisfying weekend project."
    ],
    "career_vertex": [
        "Landing the PM role at Vertex Health. Focusing on {features} was my priority.",
        "My first meeting with VP Greg Holloway. Navigating his roadmap style was a key {challenges}.",
        "Mentoring junior PM Anika Patel. Helping her research {features} was rewarding.",
        "CareConnect architecture review. Engineering indexes was one of our early {challenges}.",
        "Building a solid relationship with Anika. Mentoring her led to great coaching {wins}.",
        "Handling launch conflicts with Greg. Resolving schedule pressure was one of our major {challenges}.",
        "Writing the patient onboarding PRD. Documenting the workflow was a major team {wins}.",
        "Conducting beta tests with clinic admins, resolving security {challenges} quickly.",
        "Presenting roadmaps to stakeholders. Highlighting risks was a great professional {wins}.",
        "Celebrating sprint milestones with engineering. Bringing donuts to Plano was a small but nice {wins}."
    ],
    "formative": [
        "Learning personal finance. Correcting budgeting mistakes taught us key {lessons}.",
        "Developing home maintenance routines. Scheduling HVAC cleaning required building new {habits}.",
        "Coaching soccer. Learning to guide the players taught me invaluable {lessons}.",
        "Reflecting on woodworking lessons. Realizing quality takes time is one of my core {lessons}.",
        "Running early in the morning. Clearing my head before work became one of my daily {habits}.",
        "Managing kids screen-time. Setting clear limits required building healthy digital {habits}.",
        "Date nights with Sarah. Maintaining our connection taught us important family {lessons}.",
        "Delegating tasks at Vertex Health. Trusting Anika was one of my best career {lessons}.",
        "Saving for college funds. Setting up Texas 529 accounts became part of our long-term {habits}.",
        "Sarah launching UX design courses. Seeing her help others was a lesson in education."
    ],
    "holidays_traditions": [
        "Thanksgiving visits to Austin. Deep-frying a turkey and eating {food} is our tradition.",
        "Christmas road trips to Naperville. The kids playing snow {games} with grandparents.",
        "Back-to-school shopping at Target in Frisco, buying school gear.",
        "Annual 4th of July block party with Dave and Kim, grilling {food} on the deck.",
        "Friday night board games. Playing Catan and other {games} with the kids.",
        "Sarah and Patricia baking cookies, keeping old recipes alive in Austin.",
        "Tom Thompson showing Ethan model trains in Naperville during summer.",
        "Easter egg hunts in the yard, playing fun outdoor {games} with the Nguyen family.",
        "Anniversary dinner in Dallas, celebrating our marriage and family.",
        "Sarah's birthday traditions, making her favorite dessert and playing party {games}."
    ],
    "neurodivergence_hyperfocus": [
        "ADHD hyperfocus means I dive deep into {interests}. Linda Thompson supported my learning.",
        "Tom Thompson helped me build a telescope in Naperville. I got hyperfocused on {tools}.",
        "Writing my first Python script in middle school, spending hours on {details}.",
        "Hyperfocus on space science. Reading about exoplanet spectrometry was one of my favorite {interests}.",
        "Hacking custom smart home automations in Frisco, spending late nights on {details}.",
        "Using Jarvis to manage ADHD focus transitions. Jarvis tracks my schedule for {interests}.",
        "Reading exoplanet spectrometry papers late, research details like {tools} fascinate me.",
        "Setting up telescope equatorial mounts, focusing on Polar alignment using {tools}.",
        "Optimizing local LLMs on my workstation, tweaking GPU layers and quantization {tools}.",
        "Integrating solar flare APIs with living room lights, a fun project combining {interests} and coding."
    ]
}

def format_biographical(cat, index, base_template):
    details = {
        "mike_childhood": {
            "lessons": [
                "keep my eye on the ball and focus", "have fun and value teamwork over winning", 
                "always support my friends on the field", "shake hands with the opponents",
                "never give up even when losing", "try my best and play fair"
            ],
            "topics": ["astronomy and space", "aerospace engineering", "history and biography", "science experiments"],
            "conditions": ["our toes were frozen", "it started getting dark outside", "dinner was ready", "our gloves were soaked"],
            "treats": ["hot chocolate with marshmallows", "warm apple cider", "freshly baked cookies", "warm soup"],
            "obstacles": ["a tall oak tree", "the neighbor's gutter", "the utility wires", "a thick pine branch"],
            "subjects": ["all the world capitals", "difficult vocabulary words", "state names and flags", "scientific terms"],
            "feelings": ["exhausting but worth it", "a fun challenge", "freezing cold", "pure excitement"],
            "foods": ["hot dogs and popcorn", "peanuts and pretzels", "nachos and sodas", "burgers and fries"],
            "attractions": ["the massive water slides", "camping under the pine trees", "fishing on the quiet lake", "hiking the scenic trails"],
            "smells": ["sweet vanilla and cinnamon", "warm brown sugar", "chocolate chips melting", "freshly baked dough"],
            "milestones": ["I could finally balance on my own", "I was riding without training wheels", "I pedaled all the way down the street", "Tom let go and cheered"]
        },
        "mike_school": {
            "feelings": ["stressful but rewarding", "exhausting", "full of learning", "exciting", "highly motivating"],
            "topic": ["engineering exams", "programming projects", "calculus homework", "aerospace design drafts"],
            "locations": ["the campus library", "the dorm study lounge", "our shared apartment table", "the CS computer labs"]
        },
        "mike_early_career": {
            "lessons": ["importance of clear roadmaps", "value of developer collaboration", "how to scope an MVP", "managing stakeholder expectations"],
            "hours": ["late at night", "early in the morning", "during busy launch weeks", "on weekends before release"],
            "tools": ["database query planners", "user analytics dashboards", "sprint prioritization boards", "custom tracking sheets"]
        },
        "sarah_childhood": {
            "details": ["waking up early and baiting hooks", "having a quiet morning on the water", "listening to the lake sounds at sunrise"],
            "colors": ["colorful drawings", "watercolor paint", "sketches of nature", "oil paintings"],
            "trees": ["oak trees", "pecan trees", "backyard bushes", "tall pine trees"]
        },
        "sarah_school": {
            "studios": ["the design lab", "the art studio", "the coffee shop corner", "the library workshop"],
            "topics": ["typography layouts", "digital accessibility wireframes", "user interface designs", "mobile design systems"],
            "challenges": ["criticism from professors", "tight project deadlines", "complex web coding tasks", "balancing coursework and internships"]
        },
        "marriage_family": {
            "details": ["painting the rooms and buying furniture", "setting up the guest bedroom", "rebuilding the backyard fence"],
            "seasons": ["warm summer months", "cozy winter nights", "busy autumn days", "fresh spring mornings"],
            "feelings": ["pure joy", "exciting transitions", "beautiful memories", "meaningful growth"]
        },
        "parents_memories": {
            "lessons": ["dedication to service", "craftsmanship and precision", "patience with learning", "quiet strength and focus"],
            "activities": ["baking classic pecan pies", "building custom cedar chairs", "reading bedtime stories", "sharing historical facts"],
            "places": ["the dining table", "the garage woodshop", "the local park library", "the backyard deck"]
        },
        "chicago_to_texas": {
            "detail": ["driving through Oklahoma in a storm", "packing our SUV with winter gear", "house hunting in Frisco"],
            "challenges": ["getting used to the Texas heat", "finding running trails in Frisco", "setting up the smart home sensors"],
            "places": ["local Plano microbreweries", "Liberty Elementary school", "Little Stars daycare"]
        },
        "robert_passing": {
            "details": ["driving down to Austin to support her", "sorting through Robert's tools", "organizing the workshop shelves"],
            "lessons": ["his quiet strength and humor", "the beauty of his woodwork", "valuing time with loved ones"],
            "feelings": ["deep grief and recovery", "warm remembrance", "supporting each other through tough times"]
        },
        "career_transition": {
            "lessons": ["importance of salary negotiations", "how to handle backgrounds checks", "value of a robust onboarding timeline"],
            "feelings": ["warm memories", "some professional nervousness", "excitement for the future", "satisfaction with the final terms"],
            "challenges": ["managing background check paperwork", "relocation budget planning", "setting up the new Plano office workflow"]
        },
        "puppy_adoption": {
            "habits": ["early morning potty training walks", "cleaning up chew toy scraps", "crate training after dinner"],
            "feelings": ["immense family joy", "playful puppy energy", "funny training bloopers"],
            "milestones": ["Cooper learning to sit on command", "Cooper sleeping through the night", "Cooper successfully fetching a tennis ball"]
        },
        "new_house_search": {
            "feelings": ["excitement about the spacious backyard", "relief after sign-off", "exhaustion from packing boxes"],
            "challenges": ["negotiating mortgage interest rates", "sorting through dozens of real estate listings", "coordinating heavy moving trucks"],
            "habits": ["adjusting the garage smart lighting sensors", "watering the newly planted privacy trees", "visiting the neighborhood park on weekends"]
        },
        "career_vertex": {
            "features": ["patient onboarding flows", "billing portal integrations", "secure doctor messaging"],
            "challenges": ["engineering index conflicts", "VP milestone pressure", "user experience tradeoffs"],
            "wins": ["coaching milestones for junior PMs", "successful architecture sign-offs", "positive client feedback sessions"]
        },
        "formative": {
            "lessons": ["rushing things leads to mistakes", "patience is the key to coaching", "mentoring is highly rewarding"],
            "habits": ["early morning running routines", "setting strict screen-time limits", "organizing weekly budgets"],
            "areas": ["personal finance", "home maintenance", "career balance"]
        },
        "holidays_traditions": {
            "food": ["deep-fried turkey", "pecan pie", "freshly baked cookies", "barbecue burgers"],
            "games": ["Monopoly", "card games", "board games", "Easter egg hunts"],
            "places": ["the backyard deck", "the Naperville basement", "the Austin dining room"]
        },
        "neurodivergence_hyperfocus": {
            "interests": ["telescope alignment", "space weather APIs", "local LLMs", "Go vector search tools"],
            "details": ["staying up until 3 AM debugging", "reading research telemetry specs", "soldering humidity sensors"],
            "tools": ["GPU quantization parameters", "polar alignment mounts", "Hubble astrophotography scraping"]
        }
    }
    
    text = base_template
    
    # Perform index-based deterministic replacement
    cat_data = details.get(cat, {})
    for key, values in cat_data.items():
        placeholder = f"{{{key[:-1]}}}" # e.g. {lesson} from lessons
        if placeholder in text:
            val = values[index % len(values)]
            text = text.replace(placeholder, val)
            
    return text

current_bio_id = current_id
for cat, count in bio_categories:
    templates_list = bio_templates[cat]
    for i in range(count):
        base_tmpl = templates_list[i % len(templates_list)]
        base_text = format_biographical(cat, i, base_tmpl)
        text = f"{base_text} (Historical details log entry variant #{i+1} for our family archive.)"
        title = f"Biographical memory: {cat.replace('_', ' ').title()} #{i+1}"
        
        years_ago = random.randint(2, 15)
        ts_date = start_date - datetime.timedelta(days=years_ago * 365 + random.randint(0, 360))
        ts_ms = int(datetime.datetime(ts_date.year, ts_date.month, ts_date.day, tzinfo=datetime.timezone.utc).timestamp() * 1000)
        
        interest = round(random.uniform(0.4, 0.7), 2)
        challenge = round(random.uniform(0.1, 0.4), 2)
        urgency = 0.0
        valence = random.randint(-20, 70)
        arousal = random.randint(10, 80)
        
        importance = compute_importance(interest, challenge, urgency, is_special=(valence > 60))
        
        bio_rec = {
            "id": f"mem-{current_bio_id:04d}",
            "text": text,
            "title": title,
            "synapticTags": [cat.replace('_', '-'), "biographical", "reflection"],
            "valence": valence,
            "importance": importance,
            "arousal": arousal,
            "sessionId": f"session-biographical-{cat}-{i+1}",
            "timestampMs": ts_ms,
            "entityMentions": make_entity_mentions(text),
            "memoryType": "EPISODIC" if "childhood" in cat or "marriage" in cat else "SEMANTIC",
            "recallCount": 0,
            "interest": interest,
            "challenge": challenge,
            "urgency": urgency
        }
        current_bio_id += 1
        biographical_records.append(bio_rec)

# Write corpus-biographical.jsonl
bio_file = os.path.join(DATASET_DIR, "corpus-biographical.jsonl")
with open(bio_file, "w", encoding="utf-8") as f:
    for r in biographical_records:
        f.write(json.dumps(r) + "\n")

print(f"Wrote {len(biographical_records)} biographical records to corpus-biographical.jsonl")

# Merge into corpus.jsonl
print("Merging all corpus files...")
all_merged = []
all_merged.extend(existing_memories)
all_merged.extend(generated_records)
all_merged.extend(biographical_records)

seen_ids = set()
dedup_merged = []
for r in all_merged:
    if r["id"] not in seen_ids:
        seen_ids.add(r["id"])
        dedup_merged.append(r)
        
dedup_merged.sort(key=lambda x: x["id"])

corpus_file = os.path.join(DATASET_DIR, "corpus.jsonl")
with open(corpus_file, "w", encoding="utf-8") as f:
    for r in dedup_merged:
        f.write(json.dumps(r) + "\n")
print(f"Merged {len(dedup_merged)} records into corpus.jsonl")

# Build graphs in Python
print("Building graphs...")

# 1. Entity relations
relation_map = {}
for record in dedup_merged:
    entities = record.get("entityMentions", [])
    if len(entities) < 2:
        continue
    for i in range(len(entities)):
        for j in range(i + 1, len(entities)):
            from_e = entities[i]
            to_e = entities[j]
            key = f"{from_e['name']}|{from_e['type']}->{to_e['name']}|{to_e['type']}"
            if key not in relation_map:
                relation_map[key] = {
                    "fromEntity": from_e,
                    "toEntity": to_e,
                    "relationType": "RELATED_TO",
                    "sourceMemoryIds": []
                }
            if record["id"] not in relation_map[key]["sourceMemoryIds"]:
                relation_map[key]["sourceMemoryIds"].append(record["id"])

# Infer relation types
for key, rel in relation_map.items():
    from_t = rel["fromEntity"]["type"]
    to_t = rel["toEntity"]["type"]
    if from_t == "PERSON" and to_t == "ORGANIZATION":
        rel["relationType"] = "WORKS_ON"
    elif from_t == "PERSON" and to_t == "SOFTWARE":
        rel["relationType"] = "USES"
    elif from_t == "PERSON" and to_t == "PERSON":
        rel["relationType"] = "KNOWS"
    elif from_t == "SOFTWARE" and to_t == "SOFTWARE":
        rel["relationType"] = "DEPENDS_ON"
    elif from_t == "PERSON" and to_t == "LOCATION":
        rel["relationType"] = "LOCATED_AT"
        
entities_file = os.path.join(DATASET_DIR, "entities.jsonl")
with open(entities_file, "w", encoding="utf-8") as f:
    for rel in relation_map.values():
        f.write(json.dumps(rel) + "\n")
print(f"Wrote {len(relation_map)} entity relations to entities.jsonl")

# 2. Temporal chains
session_groups = {}
for record in dedup_merged:
    sid = record.get("sessionId")
    if sid:
        if sid not in session_groups:
            session_groups[sid] = []
        session_groups[sid].append(record)

temporal_chains = []
for sid, recs in session_groups.items():
    if len(recs) < 2:
        continue
    recs.sort(key=lambda x: x["timestampMs"])
    temporal_chains.append({
        "sessionId": sid,
        "orderedMemoryIds": [r["id"] for r in recs]
    })

temporal_file = os.path.join(DATASET_DIR, "temporal_chains.jsonl")
with open(temporal_file, "w", encoding="utf-8") as f:
    for tc in temporal_chains:
        f.write(json.dumps(tc) + "\n")
print(f"Wrote {len(temporal_chains)} temporal chains to temporal_chains.jsonl")

# 3. Hebbian edges — multi-signal co-activation
#    Signal A: Shared SPECIFIC tags (exclude broad category tags)
#    Signal B: Entity co-occurrence (same people/projects mentioned)
#    Signal C: Session-adjacent memories with topical overlap
print("Building Hebbian edges with multi-signal co-activation...")

# Tags that are too broad to indicate semantic relationship
BROAD_TAGS = {
    "parenting", "reflection", "work", "school", "hobbies", "finance",
    "health", "personal", "biographical", "morning-routine", "evening-journal",
    "calendar", "reminder", "cooking", "relaxation", "exercise", "shopping",
    "greeting", "planning", "coding", "research", "sleep"
}

edge_scores = {}  # edge_key -> float score

def add_edge_score(id_a, id_b, score):
    """Accumulate co-activation score for a memory pair."""
    key = f"{id_a}|{id_b}" if id_a < id_b else f"{id_b}|{id_a}"
    edge_scores[key] = edge_scores.get(key, 0.0) + score

# Build indexes
id_to_record = {r["id"]: r for r in dedup_merged}

# ── Signal A: Shared specific tags ──
# Only use tags that are specific enough to indicate real topical overlap
specific_tag_index = {}
for record in dedup_merged:
    tags = set(record.get("synapticTags", [])) - BROAD_TAGS
    for tag in tags:
        if tag not in specific_tag_index:
            specific_tag_index[tag] = []
        specific_tag_index[tag].append(record["id"])

for tag, mem_ids in specific_tag_index.items():
    # Still skip very large groups (>60), but now they're specific tags
    if len(mem_ids) > 60:
        continue
    # Build pairwise edges, weighted by how specific the tag group is
    # Smaller groups = more specific = stronger signal
    weight = 1.0 if len(mem_ids) <= 15 else 0.6 if len(mem_ids) <= 30 else 0.3
    for i in range(len(mem_ids)):
        for j in range(i + 1, len(mem_ids)):
            add_edge_score(mem_ids[i], mem_ids[j], weight)

# ── Signal B: Entity co-occurrence ──
# Memories mentioning the same specific entities (people, software, orgs)
entity_index = {}  # entity_name -> [memory_ids]
for record in dedup_merged:
    mentions = record.get("entityMentions", [])
    for em in mentions:
        name = em.get("name", "")
        if not name or name.lower() in ("jarvis", "mike"):  # Too ubiquitous
            continue
        if name not in entity_index:
            entity_index[name] = []
        entity_index[name].append(record["id"])

for entity, mem_ids in entity_index.items():
    if len(mem_ids) > 80 or len(mem_ids) < 2:
        continue
    # Entity co-occurrence is a strong signal
    weight = 1.5 if len(mem_ids) <= 10 else 1.0 if len(mem_ids) <= 30 else 0.5
    for i in range(len(mem_ids)):
        for j in range(i + 1, min(i + 20, len(mem_ids))):
            # Limit pairwise to avoid O(n²) explosion on large entity groups
            add_edge_score(mem_ids[i], mem_ids[j], weight)

# ── Signal C: Session-adjacent with topical overlap ──
# Memories in the same session that share ≥1 specific tag
session_index = {}
for record in dedup_merged:
    sid = record.get("sessionId")
    if sid:
        if sid not in session_index:
            session_index[sid] = []
        session_index[sid].append(record)

for sid, recs in session_index.items():
    if len(recs) < 2:
        continue
    recs.sort(key=lambda x: x.get("timestampMs", 0))
    # Connect adjacent memories in the same session
    for i in range(len(recs) - 1):
        tags_a = set(recs[i].get("synapticTags", [])) - BROAD_TAGS
        for j in range(i + 1, min(i + 3, len(recs))):  # up to 2 ahead
            tags_b = set(recs[j].get("synapticTags", [])) - BROAD_TAGS
            shared = tags_a & tags_b
            if shared:
                # Strong signal: same session + same specific topic
                add_edge_score(recs[i]["id"], recs[j]["id"], 2.0)

# ── Threshold and emit ──
# Require minimum accumulated score to create an edge
MIN_SCORE = 2.0  # At least 2 independent signals or 1 strong one
hebbian_edges = []
for edge_key, score in edge_scores.items():
    if score >= MIN_SCORE:
        parts = edge_key.split("|")
        co_act = min(int(round(score)), 10)
        hebbian_edges.append({
            "memoryIdA": parts[0],
            "memoryIdB": parts[1],
            "coActivationCount": co_act
        })

# Sort for determinism
hebbian_edges.sort(key=lambda x: (x["memoryIdA"], x["memoryIdB"]))

hebbian_file = os.path.join(DATASET_DIR, "hebbian_edges.jsonl")
with open(hebbian_file, "w", encoding="utf-8") as f:
    for edge in hebbian_edges:
        f.write(json.dumps(edge) + "\n")
print(f"Wrote {len(hebbian_edges)} Hebbian edges to hebbian_edges.jsonl")


# Generate queries and qrels in Python
print("Generating queries and qrels...")
queries = []
qrels = []

# Standard queries bank targeting different subsystems
query_presets = [
    # Vector Similarity
    ("What was on my calendar and agenda for my workday on Thursday, June 5th?", "BALANCED", ["calendar", "work"], "VECTOR_SIMILARITY", None, ["calendar"], ["june 5th", "june 5", "thursday", "rundown"]),
    ("How did the CareConnect stakeholder review meeting with Greg on June 5th go?", "BALANCED", ["careconnect", "meeting"], "VECTOR_SIMILARITY", None, ["careconnect"], ["greg", "stakeholder review", "june 5th", "june 5", "better than expected", "agreed to v1.1"]),
    ("What smart home bridge issues did we have with the living room lights not responding?", "BALANCED", ["smart-home"], "VECTOR_SIMILARITY", None, ["lights"], ["smart-home", "bridge", "not responding", "unplug it"]),
    ("How is Ethan's piano progress with Mrs. Patterson and starting Fur Elise?", "BALANCED", ["piano", "ethan-growth"], "VECTOR_SIMILARITY", None, ["piano"], ["mrs. patterson", "fur elise", "progress", "recital"]),
    ("Show me childhood memories of Naperville and Tom Thompson coaching my youth baseball team", "BALANCED", ["tom-linda", "naperville"], "VECTOR_SIMILARITY", "OLD", ["naperville"], ["tom thompson", "baseball", "coached", "youth"]),
    
    # Tag Gating
    ("Show me math homework help sessions where Ethan was stuck on division word problems", "BALANCED", ["homework", "math"], "TAG_GATING", None, ["homework"], ["division", "math", "stuck", "remainders", "spelling bee"]),
    ("What rate did First United Mortgage offer us for our refinance?", "BALANCED", ["mortgage", "refinance"], "TAG_GATING", None, ["refinance"], ["mortgage", "first united", "5.8%", "savings"]),
    ("Retrieve my memories of coaching Ethan's soccer team Frisco FC and water bottle reminders", "BALANCED", ["soccer", "practice"], "TAG_GATING", "RECENT", ["soccer"], ["frisco fc", "coaching", "shin guards", "water bottle", "tournament"]),
    
    # Valence Filter
    ("What stressful meltdown did Lily have at bedtime because of her stuffed elephant?", "DEBUGGING", ["parenting", "tantrum"], "VALENCE_FILTER", None, ["bedtime"], ["stuffed elephant", "meltdown", "screaming", "tantrum"]),
    ("What are my happiest successes at Vertex Health when Greg agreed to the CareConnect messaging phased approach?", "RECALLING", ["careconnect", "work"], "VALENCE_FILTER", None, ["careconnect"], ["greg", "messaging", "phased", "success", "agreed", "baylor"]),
    ("Show me updates about Ethan reading at a 4th grade level and piano with Mrs. Patterson", "RECALLING", ["ethan-growth"], "VALENCE_FILTER", None, ["ethan"], ["4th grade", "reading", "mrs. patterson", "piano"]),
    
    # Importance Decay
    ("What design decisions did we make with Anika about the CareConnect patient messaging feature scope?", "CRITICAL", ["careconnect", "roadmap"], "IMPORTANCE_DECAY", None, ["careconnect"], ["anika", "messaging", "feature", "scope", "design"]),
    ("What woodworking advice and hand planes did I inherit from Robert Morales?", "CRITICAL", ["robert-memory"], "IMPORTANCE_DECAY", "OLD", ["robert"], ["woodworking", "planes", "inherit", "morales", "cedar"]),
    
    # Hebbian Graph
    ("What mentoring advice did we discuss for Anika Patel to own the CareConnect patient onboarding prioritization?", "DIVERGENT", ["mentoring", "anika"], "HEBBIAN_GRAPH", None, ["anika"], ["mentoring", "patel", "onboarding", "prioritization", "coaching"]),
    ("What garage projects did I build using red oak wood and Robert Morales's hand planes?", "DIVERGENT", ["garage", "woodworking"], "HEBBIAN_GRAPH", None, ["woodworking"], ["red oak", "garage", "cabinetry", "planes"]),
    
    # Temporal Chain
    ("What SMS rate limit errors and Twilio integration issues did we hit in the sprint retro?", "THE_EXECUTOR", ["careconnect"], "TEMPORAL_CHAIN", "RECENT", ["rate limit"], ["twilio", "429", "errors", "sprint retro", "sms"]),
    ("Show me memories about Patricia Morales's knee pain, MRI, and finding doctors in Austin", "BALANCED", ["patricia", "health"], "TEMPORAL_CHAIN", None, ["patricia"], ["knee", "mri", "doctor", "austin", "hospital"]),
    
    # Entity Graph
    ("How did Greg Holloway push back on the CareConnect messaging feature and want to cut it from the MVP?", "PARANOID_SENTINEL", ["greg-conflict"], "ENTITY_GRAPH", None, ["greg"], ["messaging", "mvp", "push back", "cut", "holloway"]),
    ("What did Mrs. Patterson say about Ethan Thompson's piano lesson and starting Fur Elise?", "BALANCED", ["school", "piano"], "ENTITY_GRAPH", None, ["mrs. patterson"], ["piano", "fur elise", "lesson"])
]

if GENERATE_NEURODIVERGENT:
    query_presets.extend([
        # Neurodivergent / Hyperfocus
        ("Show me records about telescope alignment, polar alignment mounts, and tracking Jupiter's moons", "HYPERFOCUS", ["astronomy", "stargazing", "telescope"], "VECTOR_SIMILARITY", None, ["telescope"], ["alignment", "stargazing", "jupiter's moons", "polar alignment", "mount"]),
        # Neurodivergent / Divergent
        ("Retrieve memories about quantizing local LLMs, GPU layers, and local workstation memory settings", "DIVERGENT", ["artificial-intelligence", "local-llm", "coding"], "HEBBIAN_GRAPH", None, ["llm"], ["quantiz", "gpu", "layers", "workstation"]),
        # Neurodivergent / Systematizer
        ("Show me exoplanet spectrometry of K2-18b and Mars rover MMRTG power decay research", "SYSTEMATIZER", ["astronomy", "space-science", "research"], "ENTITY_GRAPH", None, ["spectrometry"], ["k2-18b", "mmrtg", "rover", "decay", "power"])
    ])

for idx, preset in enumerate(query_presets):
    qid = f"q-{idx+1:03d}"
    text, profile, filter_tags, subsystem, temp_hint, must_include, should_include = preset
    
    if profile not in ["BALANCED", "EXPLORING", "DEBUGGING", "RECALLING", "CRITICAL", "HYPERFOCUS", "SYSTEMATIZER", "DIVERGENT", "PARANOID_SENTINEL", "THE_EXECUTOR", "HIGHLY_SENSITIVE", "DEFAULT_MODE_NETWORK"]:
        profile = "BALANCED"
        
    query_rec = {
        "id": qid,
        "text": text,
        "cognitiveProfile": profile,
        "synapticFilterTags": filter_tags,
        "minValence": -128 if profile == "DEBUGGING" or profile == "PARANOID_SENTINEL" else None,
        "maxValence": -10 if profile == "DEBUGGING" else (-1 if profile == "PARANOID_SENTINEL" else None),
        "expectedSubsystem": subsystem,
        "temporalHint": temp_hint
    }
    queries.append(query_rec)
    
    keywords = [w.lower() for w in text.replace("?", "").replace("'", "").split() if len(w) > 3]
    scores = []
    for r in dedup_merged:
        r_text = r["text"].lower()
        r_title = r["title"].lower()
        
        # Check must_include
        missing_must = False
        for must in must_include:
            if must not in r_text and must not in r_title:
                missing_must = True
                break
        if missing_must:
            continue
            
        # Score based on should_include, tags, and keywords
        overlap = 0
        for should in should_include:
            if should in r_text or should in r_title:
                overlap += 4
        for tag in filter_tags:
            if tag in r.get("synapticTags", []):
                overlap += 3
        for kw in keywords:
            if kw in r_text or kw in r_title:
                overlap += 1
                
        if overlap > 0:
            scores.append((r["id"], overlap))
            
    scores.sort(key=lambda x: x[1], reverse=True)
    
    assigned = 0
    for mid, score in scores[:8]:
        grade = 3 if assigned < 2 else (2 if assigned < 5 else 1)
        qrels.append({
            "query_id": qid,
            "corpus_id": mid,
            "relevance_grade": grade
        })
        assigned += 1

# Write queries.jsonl
queries_file = os.path.join(DATASET_DIR, "queries.jsonl")
with open(queries_file, "w", encoding="utf-8") as f:
    for q in queries:
        f.write(json.dumps(q) + "\n")
print(f"Wrote {len(queries)} queries to queries.jsonl")

# Write qrels.tsv
qrels_file = os.path.join(DATASET_DIR, "qrels.tsv")
with open(qrels_file, "w", encoding="utf-8") as f:
    f.write("query_id\tcorpus_id\trelevance_grade\n")
    for qr in qrels:
        f.write(f"{qr['query_id']}\t{qr['corpus_id']}\t{qr['relevance_grade']}\n")
print(f"Wrote {len(qrels)} judgments to qrels.tsv")


# Perform final validation and write report
print("Validating dataset files...")
errors = []
warnings = []

corpus_ids = set()
for r in dedup_merged:
    cid = r.get("id")
    if not cid:
        errors.append("Memory without ID found")
    elif cid in corpus_ids:
        errors.append(f"Duplicate corpus ID: {cid}")
    else:
        corpus_ids.add(cid)
        
    if not (0.05 <= r.get("importance", 0) <= 10.0):
        errors.append(f"Corpus {cid}: importance out of range [0.05, 10.0]: {r.get('importance')}")
    if not (0 <= r.get("arousal", 0) <= 255):
        errors.append(f"Corpus {cid}: arousal out of range [0, 255]: {r.get('arousal')}")
    if not (-128 <= r.get("valence", 0) <= 127):
        errors.append(f"Corpus {cid}: valence out of range [-128, 127]: {r.get('valence')}")
    if not r.get("synapticTags"):
        warnings.append(f"Corpus {cid}: no synaptic tags")
        
query_ids = set()
for q in queries:
    qid = q.get("id")
    if not qid:
        errors.append("Query without ID found")
    elif qid in query_ids:
        errors.append(f"Duplicate query ID: {qid}")
    else:
        query_ids.add(qid)

for qr in qrels:
    qid = qr["query_id"]
    cid = qr["corpus_id"]
    if qid not in query_ids:
        errors.append(f"Qrel references unknown query: {qid}")
    if cid not in corpus_ids:
        errors.append(f"Qrel references unknown corpus ID: {cid}")

report_path = os.path.join(DATASET_DIR, "validation-report.txt")
with open(report_path, "w", encoding="utf-8") as f:
    f.write("=== Dataset Validation Report ===\n\n")
    if not errors:
        f.write("Status: PASSED\n")
    else:
        f.write("Status: FAILED\n")
        f.write(f"Errors: {len(errors)}\n")
    f.write(f"Warnings: {len(warnings)}\n\n")
    
    if errors:
        f.write("--- ERRORS ---\n")
        for idx, err in enumerate(errors):
            f.write(f"{idx+1}. {err}\n")
        f.write("\n")
        
    if warnings:
        f.write("--- WARNINGS ---\n")
        for idx, warn in enumerate(warnings):
            f.write(f"{idx+1}. {warn}\n")

print(f"Validation complete. Status: {'PASSED' if not errors else 'FAILED'}. Written report to {report_path}")

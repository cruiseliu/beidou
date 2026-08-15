drop table if exists lang_resources;
create table if not exists lang_resources
(
    `id` INTEGER PRIMARY KEY AUTOINCREMENT,
    lang_type   varchar(32),
    lang_base   varchar(32),
    lang_code   varchar(128) not null,
    lang_value  varchar(512) not null,
    lang_extend varchar(512)
    
);
CREATE INDEX IF NOT EXISTS `lang_resources_idx_lang_code` ON `lang_resources` (lang_code);


insert into lang_resources(lang_type, lang_base, lang_code, lang_value, lang_extend)
select 'zh-CN', 'game_config', config_code, substr(config_desc, 1, instr(config_desc, '(') - 1), null
from game_config;

insert into lang_resources(lang_type, lang_base, lang_code, lang_value, lang_extend)
select 'en-US', 'game_config', config_code, substr(substr(config_desc, instr(config_desc, '(') + 1), 1, instr(substr(config_desc, instr(config_desc, '(') + 1), ')') - 1), null
from game_config;

update game_config set config_desc = config_code;

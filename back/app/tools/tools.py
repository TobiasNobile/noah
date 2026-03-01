from .image_tool import image_tool
from .userInfo_tool import userInfo_tool, userInfo_gpsToCity_tool, userInfo_gpsGetPlacesAround

tools = [userInfo_tool, userInfo_gpsToCity_tool, image_tool, userInfo_gpsGetPlacesAround]
tools_by_name = {tool.name: tool for tool in tools}